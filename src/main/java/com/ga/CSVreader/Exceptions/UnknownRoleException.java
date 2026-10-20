package com.ga.CSVreader.Exceptions;

import lombok.Getter;

/**
 * Thrown when a role column is not Employee, Manager or Director.
 */
@Getter
public class UnknownRoleException extends RuntimeException {

    private final String role;

    public UnknownRoleException(String role) {
        super("Unknown role: '" + role + "'. Expected Employee, Manager or Director");
        this.role = role;
    }
}
