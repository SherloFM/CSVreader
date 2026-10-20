package com.ga.CSVreader.Service;

import com.ga.CSVreader.Model.Employee;
import com.ga.CSVreader.Model.ProcessedEmployee;
import com.ga.CSVreader.Model.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;

@Service
@RequiredArgsConstructor
public class SalaryIncreaseService {

    /** Rating must be >= 0.6 or there is no increase at all. */
    public static final double RATING_THRESHOLD = 0.6;
    /** Each full year of service adds 20% of salary. */
    public static final double YEARS_INCREASE_PER_YEAR = 0.2;

    private final ClassificationService classificationService;

    /** Rule 1: below 0.6 -> no increase. */
    public boolean isEligible(Employee employee) {
        return employee.getRating() >= RATING_THRESHOLD;
    }

    /** Role percentages: Director 0.5, Manager 0.2, Employee 0.1. */
    public double roleIncreasePercentage(Role role) {
        return switch (role) {
            case DIRECTOR -> 0.5;
            case MANAGER  -> 0.2;
            case EMPLOYEE -> 0.1;
        };
    }

    /** Full years completed since hire date. */
    public int fullYearsCompleted(Employee employee) {
        return Period.between(employee.getHireDate(), LocalDate.now()).getYears();
    }

    /** Runs every rule for one employee and produces the output object. */
    public ProcessedEmployee process(Employee employee) {

        Role role = classificationService.classify(employee.getRole());  // validates + classifies

        double increase = 0.0;
        if (isEligible(employee)) {
            double roleIncrease = employee.getSalary() * roleIncreasePercentage(role);
            double yearsIncrease = employee.getSalary()
                    * YEARS_INCREASE_PER_YEAR
                    * fullYearsCompleted(employee);
            increase = roleIncrease + yearsIncrease;
        }

        return new ProcessedEmployee(employee, employee.getSalary() + increase);
    }
}
