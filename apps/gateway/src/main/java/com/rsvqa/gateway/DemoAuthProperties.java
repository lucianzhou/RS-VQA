package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.demo-auth")
public record DemoAuthProperties(boolean enabled) {
}
