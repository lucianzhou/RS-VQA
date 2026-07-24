package com.rsvqa.gateway;

import java.util.UUID;

import org.slf4j.MDC;

final class TraceId {
    static final String MDC_KEY = "traceId";

    private TraceId() {
    }

    static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
