package com.ga.CSVreader.Service;

import com.ga.CSVreader.Model.Employee;
import com.ga.CSVreader.Model.ProcessedEmployee;
import com.ga.CSVreader.Repository.CsvRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class CsvProcessService {

    private static final Logger log = LoggerFactory.getLogger(CsvProcessService.class);

    /** Exactly 3 read-and-calculate threads + 3 write threads = 6 threads. */
    private static final int CALC_THREADS = 3;
    private static final int WRITE_THREADS = 3;

    /** Sentinel id carried by the poison pills that shut the worker loops down. */
    private static final int POISON_ID = -1;

    private final CsvRepository csvRepository;
    private final SalaryIncreaseService salaryIncreaseService;

    public Map<String, Object> processFile() {
        return processFile("test_employees.csv", "salary_increase_output.csv");
    }

    public Map<String, Object> processFile(String inputPath, String outputPath) {
        long start = System.currentTimeMillis();

        // 1. Read once, on the calling thread — the file is one small I/O burst;
        //    splitting THAT across threads would add contention, not speed.
        List<Employee> employees = csvRepository.readAll(inputPath);
        Path output = Paths.get(outputPath);
        csvRepository.createOutputFile(output);

        // 2. The two hand-off queues: one employee (then one result) per entry.
        BlockingQueue<Employee> calcQueue = new LinkedBlockingQueue<>();
        BlockingQueue<ProcessedEmployee> writeQueue = new LinkedBlockingQueue<>();

        // 3. COURSE REQUIREMENT (demo): caps concurrent calculations at 3.
        //    In practice the calc pool already has exactly 3 threads, so this is
        //    belt-and-braces — kept because the brief asks for a semaphore.
        Semaphore calcSlots = new Semaphore(CALC_THREADS);

        // 4. Exactly 6 threads, created up-front, named for readable logs.
        ExecutorService calcPool = Executors.newFixedThreadPool(CALC_THREADS, namedThreads("calc-"));
        ExecutorService writePool = Executors.newFixedThreadPool(WRITE_THREADS, namedThreads("writer-"));

        try {
            // producer: your idea — every employee is its own queue entry,
            // so it goes to whichever of the 3 threads is free
            for (Employee employee : employees) {
                calcQueue.put(employee);
            }
            // one poison pill per calc thread so each loop can exit
            for (int i = 0; i < CALC_THREADS; i++) {
                calcQueue.put(poisonEmployee());
            }

            // all 6 threads run at once: writers drain while calcs still work
            List<Future<?>> calcFutures = submit(calcPool, CALC_THREADS,
                    () -> calculateLoop(calcQueue, writeQueue, calcSlots));
            List<Future<?>> writeFutures = submit(writePool, WRITE_THREADS,
                    () -> writeLoop(writeQueue, output));

            // calculation must end BEFORE writers are told to stop,
            // otherwise a writer could exit while results are still coming
            awaitAll(calcFutures);
            for (int i = 0; i < WRITE_THREADS; i++) {
                writeQueue.put(poisonProcessed());
            }
            awaitAll(writeFutures);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // never swallow the flag
            throw new IllegalStateException("Interrupted while processing CSV", e);
        } finally {
            // shutdownNow also breaks writers out of take() if a calc thread
            // blew up and the poison pills never arrived
            stopPool(calcPool);
            stopPool(writePool);
        }

        // 5. Writers finished in random order -> make the file deterministic.
        csvRepository.sortOutputFileById(output);

        Map<String, Object> result = new HashMap<>();
        result.put("input", inputPath);
        result.put("output", outputPath);
        result.put("rowsProcessed", employees.size());
        result.put("elapsedMs", System.currentTimeMillis() - start);
        return result;
    }

    /** Takes one employee at a time; classify + raise maths; hands result to a writer. */
    private void calculateLoop(BlockingQueue<Employee> calcQueue,
                               BlockingQueue<ProcessedEmployee> writeQueue,
                               Semaphore slots) {
        while (true) {
            Employee employee;
            try {
                employee = calcQueue.take();           // blocks when queue is empty
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (employee.getId() == POISON_ID) {
                return;                                 // pill received, this thread stops
            }

            try {
                slots.acquire();                        // max 3 calculations at once
                try {
                    ProcessedEmployee result = salaryIncreaseService.process(employee);
                    log.info("{} calculated {}", Thread.currentThread().getName(), employee.getName());
                    writeQueue.put(result);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Takes one finished result at a time and appends it to the CSV. */
    private void writeLoop(BlockingQueue<ProcessedEmployee> writeQueue, Path output) {
        while (true) {
            ProcessedEmployee result;
            try {
                result = writeQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (result.getEmployee().getId() == POISON_ID) {
                return;                                 // pill received, this thread stops
            }

            csvRepository.writeRows(output, List.of(result));
            log.info("{} wrote {}", Thread.currentThread().getName(), result.getEmployee().getName());
        }
    }

    private List<Future<?>> submit(ExecutorService pool, int count, Runnable task) {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(pool.submit(task));
        }
        return futures;
    }

    /** Waits for every thread in a phase; rethrows the first real failure. */
    private void awaitAll(List<Future<?>> futures) throws InterruptedException {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;      // e.g. UnknownRoleException from a bad row
                }
                throw new IllegalStateException("A worker thread failed", cause);
            }
        }
    }

    private void stopPool(ExecutorService pool) {
        pool.shutdownNow();                             // interrupt idle/blocked workers
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS); // give them a moment to unwind
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }

    /** BlockingQueue forbids null elements, so pills are real objects with a magic id. */
    private Employee poisonEmployee() {
        return new Employee(POISON_ID, "POISON", 0.0, null, "POISON", 0.0);
    }

    private ProcessedEmployee poisonProcessed() {
        return new ProcessedEmployee(poisonEmployee(), 0.0);
    }
}
