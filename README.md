# CSVreader — Salary Increase Processor

A Spring Boot application that reads an employee CSV file, works out each person's
salary increase, and writes the updated data to a new CSV file.

The processing is done concurrently: **3 read-and-calculate threads** hand results to
**3 write threads** through blocking queues, with a **semaphore** limiting concurrent
calculations.

---

## 1. How to run

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

Or run `CsVreaderApplication` directly from IntelliJ.

The server starts on **port 8080**. The input file `test_employees.csv` and the output
file `salary_increase_output.csv` both live in the project root — no upload is needed.

---

## 2. The API

There is exactly **one endpoint**.

| | |
|---|---|
| **Method** | `GET` |
| **URL** | `http://localhost:8080/api/csv/process` |
| **Params** | none |
| **Body** | none |
| **Headers** | none |

**Example response (`200 OK`):**

```json
{
    "output": "salary_increase_output.csv",
    "input": "test_employees.csv",
    "rowsProcessed": 7,
    "elapsedMs": 27
}
```

| Field | Meaning |
|---|---|
| `input` | the file that was read |
| `output` | the file that was written |
| `rowsProcessed` | how many employees were processed |
| `elapsedMs` | wall-clock duration of the whole job in milliseconds |

Each call **overwrites** `salary_increase_output.csv` with a fresh result, so the
output always reflects the most recent request.

### Testing it in Postman

1. Start the app and wait for `Tomcat started on port 8080`.
2. In Postman: method **GET**, URL `http://localhost:8080/api/csv/process`.
3. **Send** → the JSON above comes back.
4. Open `salary_increase_output.csv` in the project root to see the result.

---

## 3. Input and output format

Both files use the **same 6 tab-separated columns** — the output has the *same shape*
as the input, only the `Salary` value is updated. Neither file has a header row.

```
ID <tab> Name <tab> Salary <tab> HireDate <tab> Role <tab> Rating
```

**`test_employees.csv` (input):**

```
1	Alice	52000.0	2019-05-12	Employee	0.8
2	Bob	68000.0	2020-08-23	Manager	0.6
3	Charlie	75000.0	2021-03-19	Director	0.7
4	Diana	60000.0	2018-11-05	Manager	0.5
5	Eve	54000.0	2019-02-14	Employee	0.9
6	Frank	83000.0	2017-07-29	Director	0.4
7	Grace	71000.0	2021-10-10	Manager	0.6
```

**`salary_increase_output.csv` (output, sorted by ID):**

```
1	Alice	130000.00	2019-05-12	Employee	0.8
2	Bob	163200.00	2020-08-23	Manager	0.6
3	Charlie	187500.00	2021-03-19	Director	0.7
4	Diana	60000.00	2018-11-05	Manager	0.5
5	Eve	135000.00	2019-02-14	Employee	0.9
6	Frank	83000.00	2017-07-29	Director	0.4
7	Grace	156200.00	2021-10-10	Manager	0.6
```

---

## 4. The business rules

For each employee:

1. **Rating gate.** If `rating < 0.6`, the employee receives **no increase at all** —
   salary is written back unchanged. Exactly `0.6` **passes** (the rule is *below* 0.6).
   → Diana (0.5) and Frank (0.4) are untouched in the example above.

2. **Role increase** — applied once, as a percentage of the current salary:

   | Role | Increase |
   |---|---|
   | Director | 50% (0.5) |
   | Manager | 20% (0.2) |
   | Employee | 10% (0.1) |

3. **Years-of-service increase** — **20% (0.2) per full year completed** since the hire
   date, also a percentage of the current salary.

```
newSalary = salary + (salary x rolePercentage) + (salary x 0.2 x fullYears)
```

Worked example — **Alice**, Employee, 52,000, hired 2019-05-12 (7 full years):

```
role increase   = 52000 x 0.1        =  5,200.00
years increase  = 52000 x 0.2 x 7    = 72,800.00
new salary      = 52000 + 5200 + 72800 = 130,000.00
```

Because the years rule depends on today's date, the years figure (and so the new
salaries) grow over time.

### Classification

The raw role text from the CSV is mapped to a `Role` enum by `ClassificationService`:

| CSV text | Classified as |
|---|---|
| `Employee` (any case, spaces trimmed) | `Role.EMPLOYEE` |
| `Manager` | `Role.MANAGER` |
| `Director` | `Role.DIRECTOR` |
| anything else | `UnknownRoleException` → HTTP 400 |

---

## 5. Architecture (MVC + Service + Repository)

```
src/main/java/com/ga/CSVreader/
├── CsVreaderApplication.java
├── Controller/
│   ├── CsvController.java             GET /api/csv/process
│   └── ExceptionHandlers.java         custom exceptions -> clean JSON responses
├── Service/
│   ├── ClassificationService.java     classify() : CSV role text -> Role enum
│   ├── SalaryIncreaseService.java     the raise rules / maths
│   └── CsvProcessService.java         thread pooling, queues, semaphore
├── Repository/
│   └── CsvRepository.java             file I/O only: read, append, sort
├── Model/
│   ├── Role.java                      enum EMPLOYEE / MANAGER / DIRECTOR
│   ├── Employee.java                  one input row
│   └── ProcessedEmployee.java         one row's result (employee + newSalary)
└── Exceptions/
    ├── FileReadException.java
    ├── FileWriteException.java
    ├── CsvColumnCountException.java
    ├── InvalidEmployeeDataException.java
    ├── InvalidDateException.java
    └── UnknownRoleException.java
```

**Layering rules:** the `Controller` holds no logic — it calls the `Service`. The
`Service` holds all business rules. The `Repository` does file I/O and nothing else.
`Model` classes are data only (Lombok `@Getter/@Setter` etc.).

---

## 6. Concurrency design: thread pooling, queues and the semaphore

### 6.1 The pipeline

```
                        calling thread (the HTTP request)
                                   │
                    reads the file once, creates the output file
                                   │
              ┌────────────────────┴────────────────────┐
              │  calcQueue : BlockingQueue<Employee>     │   one Employee per entry
              └────────────────────┬────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
   ┌─────────┐               ┌─────────┐               ┌─────────┐
   │ calc-1  │               │ calc-2  │               │ calc-3  │   ExecutorService
   │         │               │         │               │         │   (3 fixed threads)
   │ take()  │               │ take()  │               │ take()  │
   │  ...    │               │  ...    │               │  ...    │
   │ SEMAPHORE(3): acquire -> calculate -> release       │         │
   │         │               │         │               │         │
   │ classify│               │ classify│               │ classify│
   │ + maths │               │ + maths │               │ + maths │
   │         │               │         │               │         │
   │ put()   │               │ put()   │               │ put()   │
   └────┬────┘               └────┬────┘               └────┬────┘
        └──────────────────────────┼──────────────────────────┘
                                   │
                                   ▼
      ┌────────────────────────────────────────────────────────┐
      │  writeQueue : BlockingQueue<ProcessedEmployee>          │   one result per entry
      └────────────────────────────┬───────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
   ┌──────────┐             ┌──────────┐             ┌──────────┐
   │ writer-1 │             │ writer-2 │             │ writer-3 │   ExecutorService
   │          │             │          │             │          │   (3 fixed threads)
   │ take()   │             │ take()   │             │ take()   │
   │ format   │             │ format   │             │ format   │
   │ append   │             │ append   │             │ append   │  synchronized(fileLock)
   └────┬─────┘             └────┬─────┘             └────┬─────┘
        └──────────────────────────┼──────────────────────────┘
                                   │
                                   ▼
                     salary_increase_output.csv
                                   │
                       sortOutputFileById()  (after all 6 join)
                                   │
                                   ▼
                 rows in ID order, returned as JSON summary
```

### 6.2 Why a thread pool

`Executors.newFixedThreadPool(3)` creates exactly **3 threads up front** and reuses
them for every row. Creating a thread per row would be wasteful; the pool also gives
us a hard bound on concurrency and graceful shutdown. Two pools are used — one for
calculation, one for writing — so **6 threads in total**, and the log lines name them
(`calc-1`, `writer-2`, ...) so the parallelism is visible.

### 6.3 Why two blocking queues

`BlockingQueue` is the standard way for threads to hand work to each other without
manual `wait()/notify()`:

* `calcQueue<Employee>` — **one employee per entry**. The producer offers rows, and
  each goes to whichever calc thread is free. This is *dynamic load balancing*: a
  thread is only idle when the queue is empty, unlike a static split where one thread
  may sit idle holding slow rows.
* `writeQueue<ProcessedEmployee>` — one finished result per entry, drained by the
  writer threads.

`take()` blocks when the queue is empty, so no busy-waiting. Because both queues are
unbounded, a fast producer can never block a slow consumer into a deadlock.

The two phases overlap: writers start draining while calc threads are still working,
so the job is a real **pipeline**, not two sequential steps.

### 6.4 Why a semaphore

```java
Semaphore calcSlots = new Semaphore(CALC_THREADS);   // permits = 3

slots.acquire();          // allowed only if a permit is free
try {
    ...classify + salary maths...
} finally {
    slots.release();      // always returned, even if the maths throws
}
```

The semaphore caps **concurrent calculations at 3**. It is released in a `finally`
block so a throwing row can never leak a permit and stall the pipeline.

*This is a course requirement (an explicit semaphore was asked for) and is marked as
such in the code. Strictly, the 3-thread calculation pool already bounds concurrency —
the semaphore is belt-and-braces on top of it. Note the trade-off: a semaphore is
required if the pool size ever exceeds the limit you want to enforce.*

### 6.5 Shutdown: poison pills

`BlockingQueue` rejects `null`, so threads are stopped with **poison pills** — sentinel
objects whose `id` is `-1`:

* 3 pills go into `calcQueue` (one per calc thread; each loop exits on receipt).
* Only **after** every calc thread has finished are 3 pills put into `writeQueue`.

That ordering matters: sending the writer pills early would let a writer exit while
results are still in flight, silently losing rows.

### 6.6 Errors, ordering and safety

* **Errors** — a failing calc thread's exception is captured by its `Future` and
  rethrown to the caller via `Future.get()`, so a bad row fails the request loudly
  with its line number instead of vanishing inside a thread.
* **Ordering** — 3 writers finish in random order, so rows land shuffled on disk
  (visible in the logs: Alice, Charlie, Bob...). After all 6 threads join,
  `sortOutputFileById()` rewrites the file in numeric ID order, giving deterministic
  output for the same input.
* **Thread safety on the file** — all writers share one `Object fileLock` and append
  via `StandardOpenOption.APPEND`. Without the lock, two threads could interleave a
  half-written line and corrupt the CSV.
* **No zombie threads** — `shutdownNow()` in a `finally` block interrupts any worker
  still blocked in `take()` if another thread failed, so the JVM always exits.

### 6.7 Evidence that it really is concurrent

The application log for a single request shows three calc threads active in the same
millisecond and writers finishing out of order:

```
calc-2 calculated Bob
calc-1 calculated Alice          <- three different threads, same millisecond
calc-3 calculated Charlie
calc-2 calculated Diana          <- load balancing: calc-2 picked up more work
calc-3 calculated Frank
calc-1 calculated Eve
calc-2 calculated Grace
writer-2 wrote Alice
writer-3 wrote Charlie           <- writers completed out of order
writer-1 wrote Bob
writer-2 wrote Diana
writer-3 wrote Frank
writer-1 wrote Eve
writer-2 wrote Grace
```

7 rows were split across the calc threads as **3 / 2 / 2**, which is the dynamic
balancing working — not a fixed split.

---

## 7. Error handling

Each failure mode has its own exception class in the `Exceptions` package, and
`ExceptionHandlers` (`@RestControllerAdvice`) converts them into JSON responses:

| Exception | Cause | HTTP status |
|---|---|---|
| `FileReadException` | input file missing/unreadable | `404` |
| `FileWriteException` | output file not writable | `500` |
| `CsvColumnCountException` | a line doesn't have 6 columns | `422` |
| `InvalidEmployeeDataException` | ID / Salary / Rating not a number | `422` |
| `InvalidDateException` | hire date matches no accepted format | `422` |
| `UnknownRoleException` | role is not Employee/Manager/Director | `400` |

The response body carries the useful detail as fields, not just prose:

```json
{
    "status": 422,
    "error": "Unprocessable Entity",
    "message": "Bad value for Salary on line 1: 'abc' in row: 1\tAlice\tabc\t...",
    "lineNumber": 1,
    "field": "Salary",
    "value": "abc"
}
```

**Accepted hire-date formats:** `yyyy-MM-dd`, `dd/MM/yyyy`, `d/M/yy`.

---

## 8. Delimiter handling

The input file is **tab-separated**, so `CsvRepository.split()` inspects each line and
uses a tab when one is present, otherwise a comma. That keeps the reader tolerant of
both a tab-separated file like `test_employees.csv` and an ordinary comma CSV.

Note the reader does not understand quoted fields (`"Doe, John"`); unquoted values are
assumed not to contain the delimiter.

---

## 9. Notes for review

* Lombok (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@ToString`)
  generates the accessors — annotation processing must be enabled in the IDE.
* `SalaryIncreaseService` splits the rules into small public methods
  (`isEligible`, `roleIncreasePercentage`, `fullYearsCompleted`) so each rule is
  individually testable; `process()` combines them.
* Thresholds are named constants (`RATING_THRESHOLD`, `YEARS_INCREASE_PER_YEAR`)
  rather than magic numbers.
* `synchronized (fileLock)` is safe because Spring beans are singletons — all writer
  threads see the same `CsvRepository` instance, so they share the same lock.
