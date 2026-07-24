package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.gemini")
public record GeminiProviderProperties(
        boolean enabled,
        String apiKey,
        String model,
        int timeoutSeconds,
        int maxRetries,
        int maxOutputTokens,
        double temperature
) {
    public GeminiProviderProperties {
        model = model == null || model.isBlank() ? "gemini-2.5-flash" : model.trim();
        timeoutSeconds = Math.max(5, Math.min(timeoutSeconds, 180));
        maxRetries = Math.max(0, Math.min(maxRetries, 4));
        maxOutputTokens = Math.max(128, Math.min(maxOutputTokens, 8192));
        temperature = Math.max(0.0, Math.min(temperature, 1.0));
    }

    boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
