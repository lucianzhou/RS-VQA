package com.rsvqa.gateway;

public class ProviderAdmissionException extends RuntimeException {

    private final String reason;
    private final long retryAfterSeconds;

    ProviderAdmissionException(String reason, String message, long retryAfterSeconds) {
        super(message);
        this.reason = reason;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    String reason() {
        return reason;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
