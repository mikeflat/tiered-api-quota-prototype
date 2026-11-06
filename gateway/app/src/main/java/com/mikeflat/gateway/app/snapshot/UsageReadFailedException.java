package com.mikeflat.gateway.app.snapshot;

public class UsageReadFailedException extends RuntimeException {

    public UsageReadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
