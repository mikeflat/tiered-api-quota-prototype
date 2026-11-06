package com.mikeflat.gateway.app.metrics;

public class MetricsReadFailedException extends RuntimeException {

    public MetricsReadFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
