package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.model-service")
public record ModelServiceProperties(
        String baseUrl,
        int timeoutSeconds,
        long maxFileBytes
) {
}
