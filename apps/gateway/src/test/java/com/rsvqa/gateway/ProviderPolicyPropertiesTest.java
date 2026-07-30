package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProviderPolicyPropertiesTest {

    @Test
    void clampsUnsafePolicyValuesAndKeepsUnknownPricingExplicit() {
        ProviderPolicyProperties properties = new ProviderPolicyProperties(
                true,
                new ProviderPolicyProperties.Limits(0, 99, 0, -1, 0),
                null,
                new ProviderPolicyProperties.Circuit(1, Duration.ofMillis(10)),
                new ProviderPolicyProperties.Pricing("", Map.of())
        );

        assertThat(properties.vision().requestsPerMinute()).isPositive();
        assertThat(properties.vision().maxConcurrent()).isLessThanOrEqualTo(16);
        assertThat(properties.vision().maxItemsPerRequest()).isPositive();
        assertThat(properties.vision().dailyTokenBudget()).isPositive();
        assertThat(properties.agent()).isNotNull();
        assertThat(properties.circuit().failureThreshold()).isGreaterThanOrEqualTo(2);
        assertThat(properties.circuit().openDuration()).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(properties.pricing().version()).isEqualTo("unconfigured");
    }
}
