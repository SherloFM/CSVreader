package com.ga.CSVreader.Controller;

import com.ga.CSVreader.Service.CsvProcessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MVC entry point. No business logic here — it only calls the service
 * and hands the result back to the caller.
 *
 * The input file (test_employees.csv) and the output file
 * (salary_increase_output.csv) are fixed, both in the project root.
 */
@RestController
@RequestMapping("/api/csv")
@RequiredArgsConstructor
public class CsvController {

    private final CsvProcessService csvProcessService;

    /** Processes test_employees.csv -> salary_increase_output.csv */
    @GetMapping("/process")
    public ResponseEntity<Map<String, Object>> process() {
        return ResponseEntity.ok(csvProcessService.processFile());
    }
}
