package com.ga.CSVreader.Service;

import com.ga.CSVreader.Exceptions.UnknownRoleException;
import com.ga.CSVreader.Model.Role;
import org.springframework.stereotype.Service;

@Service
public class ClassificationService {

    /**
     * Maps the raw role string from the CSV to a Role enum.
     * Case-insensitive: "manager", "MANAGER", " Manager " all work.
     */
    public Role classify(String rawRole) {
        if (rawRole == null) {
            throw new UnknownRoleException(rawRole);
        }
        return switch (rawRole.trim().toLowerCase()) {
            case "employee" -> Role.EMPLOYEE;
            case "manager"  -> Role.MANAGER;
            case "director" -> Role.DIRECTOR;
            default         -> throw new UnknownRoleException(rawRole);
        };
    }
}
