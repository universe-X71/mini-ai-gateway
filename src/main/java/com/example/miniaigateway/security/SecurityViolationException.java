package com.example.miniaigateway.security;

public class SecurityViolationException extends RuntimeException {
    private final String type;

    public SecurityViolationException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String getType() {
        return type;
    }
}