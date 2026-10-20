package com.ga.CSVreader.Model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Employee {

    private int id;
    private String name;
    private double salary;
    private LocalDate hireDate;   // raw role string straight from the CSV
    private String role;
    private double rating;
}
