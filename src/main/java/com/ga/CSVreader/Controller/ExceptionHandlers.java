package com.ga.CSVreader.Controller;

import com.ga.CSVreader.Exceptions.CsvColumnCountException;
import com.ga.CSVreader.Exceptions.FileReadException;
import com.ga.CSVreader.Exceptions.FileWriteException;
import com.ga.CSVreader.Exceptions.InvalidDateException;
import com.ga.CSVreader.Exceptions.InvalidEmployeeDataException;
import com.ga.CSVreader.Exceptions.UnknownRoleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the custom exceptions from the Repository/Service layer
 * into clean JSON responses instead of raw 500 stack traces.
 */
@RestControllerAdvice
public class ExceptionHandlers {

    /** Bad role value in the CSV, e.g. "CEO". */
    @ExceptionHandler(UnknownRoleException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownRole(UnknownRoleException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), Map.of("role", String.valueOf(e.getRole())));
    }

    /** A line didn't have 6 columns. */
    @ExceptionHandler(CsvColumnCountException.class)
    public ResponseEntity<Map<String, Object>> handleColumnCount(CsvColumnCountException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), Map.of(
                "lineNumber", e.getLineNumber(),
                "expectedColumns", e.getExpectedColumns(),
                "actualColumns", e.getActualColumns()));
    }

    /** A number column (ID, Salary, rating) wasn't a number. */
    @ExceptionHandler(InvalidEmployeeDataException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidData(InvalidEmployeeDataException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), Map.of(
                "lineNumber", e.getLineNumber(),
                "field", e.getField(),
                "value", e.getValue()));
    }

    /** Hire date didn't match any accepted format. */
    @ExceptionHandler(InvalidDateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDate(InvalidDateException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), Map.of(
                "lineNumber", e.getLineNumber(),
                "value", e.getValue()));
    }

    /** Input file missing / unreadable. */
    @ExceptionHandler(FileReadException.class)
    public ResponseEntity<Map<String, Object>> handleFileRead(FileReadException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), Map.of("filePath", e.getFilePath()));
    }

    /** Output file couldn't be written (permissions, disk error). */
    @ExceptionHandler(FileWriteException.class)
    public ResponseEntity<Map<String, Object>> handleFileWrite(FileWriteException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), Map.of("filePath", e.getFilePath()));
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.putAll(details);
        return ResponseEntity.status(status).body(body);
    }
}
