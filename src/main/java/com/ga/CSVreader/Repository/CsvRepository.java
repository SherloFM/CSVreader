package com.ga.CSVreader.Repository;

import com.ga.CSVreader.Exceptions.CsvColumnCountException;
import com.ga.CSVreader.Exceptions.FileReadException;
import com.ga.CSVreader.Exceptions.InvalidDateException;
import com.ga.CSVreader.Exceptions.InvalidEmployeeDataException;
import com.ga.CSVreader.Exceptions.FileWriteException;
import com.ga.CSVreader.Exceptions.InvalidEmployeeDataException;
import com.ga.CSVreader.Model.Employee;
import com.ga.CSVreader.Model.ProcessedEmployee;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Dumb file I/O only. No business rules in here —
 * reading and writing lines is the whole job of this class.
 */
@Repository
public class CsvRepository {

    /** Accepted hire-date formats, tried in order. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,          // 2019-05-12
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), // 12/05/2019
            DateTimeFormatter.ofPattern("d/M/yy")      // 12/5/19
    );

    public List<Employee> readAll() {
        return readAll(Paths.get("test_employees.csv"));
    }

    public List<Employee> readAll(String filePath) {
        return readAll(Paths.get(filePath));
    }

    public List<Employee> readAll(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new FileReadException(path.toString(), e);
        }

        List<Employee> employees = new ArrayList<>();
        int lineNumber = 0;

        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }

            String[] columns = split(line);

            if (columns.length != 6) {
                throw new CsvColumnCountException(lineNumber, 6, columns.length, line);
            }

            employees.add(toEmployee(columns, line, lineNumber));
        }

        return employees;
    }

    /**
     * The file may be tab-separated or comma-separated;
     * pick the delimiter from the line itself.
     */
    private String[] split(String line) {
        if (line.indexOf('\t') >= 0) {
            return line.split("\t", -1);
        }
        return line.split(",", -1);
    }

    private Employee toEmployee(String[] columns, String line, int lineNumber) {
        int id = parseInt(columns[0].trim(), "ID", line, lineNumber);
        String name = columns[1].trim();
        double salary = parseDouble(columns[2].trim(), "Salary", line, lineNumber);
        LocalDate hireDate = parseDate(columns[3].trim(), line, lineNumber);
        String role = columns[4].trim();
        double rating = parseDouble(columns[5].trim(), "rating", line, lineNumber);

        return new Employee(id, name, salary, hireDate, role, rating);
    }

    private int parseInt(String value, String field, String line, int lineNumber) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidEmployeeDataException(lineNumber, field, value, line, e);
        }
    }

    private double parseDouble(String value, String field, String line, int lineNumber) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new InvalidEmployeeDataException(lineNumber, field, value, line, e);
        }
    }

    private LocalDate parseDate(String value, String line, int lineNumber) {
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new InvalidDateException(lineNumber, value, line, null);
    }

    // ==================== WRITE SIDE ====================

    /**
     * One lock shared by all 3 writer threads.
     * Without it, two threads could open the file at the same time and
     * their rows would interleave / corrupt each other.
     */
    private final Object fileLock = new Object();

    /** Creates (or truncates) the empty output file. No header — same as the input file. */
    public void createOutputFile(Path path) {
        synchronized (fileLock) {
            try {
                Files.write(path, new byte[0]);   // create if missing, wipe if present
            } catch (IOException e) {
                throw new FileWriteException(path.toString(), e);
            }
        }
    }

    /** Formats and appends the given results to the output file. Thread-safe. */
    public void writeRows(Path path, List<ProcessedEmployee> results) {
        StringBuilder batch = new StringBuilder();
        for (ProcessedEmployee result : results) {
            batch.append(formatRow(result)).append(System.lineSeparator());
        }
        synchronized (fileLock) {
            try {
                Files.write(path, batch.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new FileWriteException(path.toString(), e);
            }
        }
    }

    /** Formats one result into a tab-separated line: same 6 columns, new salary. */
    public String formatRow(ProcessedEmployee r) {
        Employee e = r.getEmployee();
        return String.join("\t",
                String.valueOf(e.getId()),        // 1
                e.getName(),                      // Alice
                fmt(r.getNewSalary()),            // 130000.00   <- ONLY changed value
                e.getHireDate().toString(),       // 2019-05-12
                e.getRole(),                      // Employee    <- raw string, as in input
                fmt(e.getRating()));              // 0.80
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The 3 writer threads finish in random order, so rows land shuffled.
     * Called once after all threads join: sorts every row by ID and rewrites.
     */
    public void sortOutputFileById(Path path) {
        synchronized (fileLock) {
            List<String> lines;
            try {
                lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FileWriteException(path.toString(), e);
            }
            if (lines.isEmpty()) {
                return;
            }

            lines.sort(Comparator.comparing(CsvRepository::extractId));  // no header to protect

            try {
                Files.write(path, (String.join(System.lineSeparator(), lines) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new FileWriteException(path.toString(), e);
            }
        }
    }

    /** Leading ID out of a tab-separated row, parsed as int (1,2,10 not 1,10,2). */
    private static int extractId(String row) {
        try {
            return Integer.parseInt(row.substring(0, row.indexOf('\t')).trim());
        } catch (NumberFormatException e) {
            throw new InvalidEmployeeDataException(-1, "ID", row, row, e);
        }
    }
}
