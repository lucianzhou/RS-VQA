package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.knowledge-service")
public record KnowledgeServiceProperties(
        String baseUrl,
        int timeoutSeconds,
        String defaultIndexVersion
) {
}
