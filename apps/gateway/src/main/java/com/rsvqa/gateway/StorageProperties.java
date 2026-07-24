package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.storage")
public record StorageProperties(String root) {
}
