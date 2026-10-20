package com.ga.CSVreader.Model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The result of the salary calculation for one employee.
 * The original row is kept untouched; only the salary is updated,
 * so the writer threads just format this back into a CSV line.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ProcessedEmployee {

    private Employee employee;   // original row, untouched
    private double newSalary;    // the only thing that changed
}
