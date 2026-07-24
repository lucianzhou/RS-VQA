package com.rsvqa.gateway;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        Map<String, Object> details,
        boolean retryable
) {
    static ApiError of(String code, String message, boolean retryable) {
        return new ApiError(code, message, TraceId.current(), Instant.now(), Map.of(), retryable);
    }
}
