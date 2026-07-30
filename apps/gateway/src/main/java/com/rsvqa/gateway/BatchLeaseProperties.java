package com.rsvqa.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.batch")
public record BatchLeaseProperties(
        boolean recoveryEnabled,
        Duration leaseDuration,
        Duration recoveryInterval
) {
    public BatchLeaseProperties {
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(10) : leaseDuration;
        recoveryInterval = recoveryInterval == null ? Duration.ofSeconds(30) : recoveryInterval;
        if (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0) {
            throw new IllegalArgumentException("rsvqa.batch.lease-duration must be at least 5 seconds.");
        }
        if (recoveryInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("rsvqa.batch.recovery-interval must be at least 1 second.");
        }
    }
}
