package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when a line does not contain the expected number of columns
 * (missing/extra delimiter, or an unquoted value containing the delimiter).
 */
@Getter
public class CsvColumnCountException extends RuntimeException {

    private final int lineNumber;
    private final int expectedColumns;
    private final int actualColumns;

    public CsvColumnCountException(int lineNumber, int expectedColumns, int actualColumns, String line) {
        super("Line " + lineNumber + " has " + actualColumns + " columns, expected "
                + expectedColumns + ": " + line);
        this.lineNumber = lineNumber;
        this.expectedColumns = expectedColumns;
        this.actualColumns = actualColumns;
    }
}
