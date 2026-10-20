package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when a numeric column (ID, Salary, rating) cannot be parsed
 * into a number.
 */
@Getter
public class InvalidEmployeeDataException extends RuntimeException {

    private final int lineNumber;
    private final String field;
    private final String value;

    public InvalidEmployeeDataException(int lineNumber, String field, String value,
                                        String line, Throwable cause) {
        super("Bad value for " + field + " on line " + lineNumber + ": '" + value
                + "' in row: " + line, cause);
        this.lineNumber = lineNumber;
        this.field = field;
        this.value = value;
    }
}
