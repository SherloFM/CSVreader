package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when the output CSV cannot be written
 * (permissions, disk error, bad path).
 */
@Getter
public class FileWriteException extends RuntimeException {

    private final String filePath;

    public FileWriteException(String filePath, Throwable cause) {
        super("Could not write to file: " + filePath, cause);
        this.filePath = filePath;
    }
}
