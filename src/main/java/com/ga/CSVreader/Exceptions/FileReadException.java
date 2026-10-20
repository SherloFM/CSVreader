package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when the CSV file cannot be opened/read at all
 * (missing file, permissions, IO error).
 */
@Getter
public class FileReadException extends RuntimeException {

    private final String filePath;

    public FileReadException(String filePath, Throwable cause) {
        super("Could not read file: " + filePath, cause);
        this.filePath = filePath;
    }
}
