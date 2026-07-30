package com.rsvqa.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.provider-policy")
public record ProviderPolicyProperties(
        boolean enabled,
        Limits vision,
        Limits agent,
        Circuit circuit,
        Pricing pricing
) {

    public ProviderPolicyProperties {
        vision = vision == null ? Limits.visionDefaults() : vision;
        agent = agent == null ? Limits.agentDefaults() : agent;
        circuit = circuit == null ? Circuit.defaults() : circuit;
        pricing = pricing == null ? Pricing.unknown() : pricing;
    }

    public record Limits(
            int requestsPerMinute,
            int maxConcurrent,
            int maxItemsPerRequest,
            long dailyTokenBudget,
            int tokenReservation
    ) {
        public Limits {
            requestsPerMinute = clamp(requestsPerMinute, 1, 600, 12);
            maxConcurrent = clamp(maxConcurrent, 1, 16, 2);
            maxItemsPerRequest = clamp(maxItemsPerRequest, 1, 50, 1);
            dailyTokenBudget = clamp(dailyTokenBudget, 1_000L, 100_000_000L, 100_000L);
            tokenReservation = clamp(tokenReservation, 128, 100_000, 8_192);
        }

        static Limits visionDefaults() {
            return new Limits(12, 2, 1, 100_000, 8_192);
        }

        static Limits agentDefaults() {
            return new Limits(20, 1, 1, 200_000, 40_000);
        }
    }

    public record Circuit(int failureThreshold, Duration openDuration) {
        public Circuit {
            failureThreshold = clamp(failureThreshold, 2, 20, 3);
            if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
                openDuration = Duration.ofSeconds(30);
            }
            if (openDuration.compareTo(Duration.ofSeconds(5)) < 0) {
                openDuration = Duration.ofSeconds(5);
            }
            if (openDuration.compareTo(Duration.ofMinutes(15)) > 0) {
                openDuration = Duration.ofMinutes(15);
            }
        }

        static Circuit defaults() {
            return new Circuit(3, Duration.ofSeconds(30));
        }
    }

    public record Pricing(String version, Map<String, Price> models) {
        public Pricing {
            version = version == null || version.isBlank() ? "unconfigured" : version.trim();
            Map<String, Price> normalized = new LinkedHashMap<>();
            if (models != null) {
                models.forEach((key, value) -> {
                    if (key != null && !key.isBlank() && value != null) {
                        normalized.put(key.trim().toLowerCase(java.util.Locale.ROOT), value);
                    }
                });
            }
            models = Map.copyOf(normalized);
        }

        static Pricing unknown() {
            return new Pricing("unconfigured", Map.of());
        }
    }

    public record Price(String inputUsdPerMillion, String outputUsdPerMillion) {
        public Price {
            inputUsdPerMillion = normalizePrice(inputUsdPerMillion);
            outputUsdPerMillion = normalizePrice(outputUsdPerMillion);
        }

        private static String normalizePrice(String value) {
            return value == null || value.isBlank() ? "" : value.trim();
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(value, max));
    }

    private static long clamp(long value, long min, long max, long fallback) {
        return value <= 0 ? fallback : Math.max(min, Math.min(value, max));
    }
}
