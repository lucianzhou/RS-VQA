package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.demo-environment")
public record DemoEnvironmentProperties(
        boolean enabled,
        String sourceRoot,
        String modelReleaseId
) {
}
