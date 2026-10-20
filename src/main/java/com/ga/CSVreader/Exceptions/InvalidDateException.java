package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when the Hire date column does not match any of the
 * accepted date formats (yyyy-MM-dd, dd/MM/yyyy, d/M/yy).
 */
@Getter
public class InvalidDateException extends RuntimeException {

    private final int lineNumber;
    private final String value;

    public InvalidDateException(int lineNumber, String value, String line, Throwable cause) {
        super("Unrecognised hire date '" + value + "' on line " + lineNumber
                + " (expected yyyy-MM-dd, dd/MM/yyyy or d/M/yy) in row: " + line, cause);
        this.lineNumber = lineNumber;
        this.value = value;
    }
}
