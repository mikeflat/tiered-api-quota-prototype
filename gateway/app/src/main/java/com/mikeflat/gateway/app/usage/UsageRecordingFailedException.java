package com.mikeflat.gateway.app.usage;

public class UsageRecordingFailedException extends RuntimeException {

    public UsageRecordingFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
