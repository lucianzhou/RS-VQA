package com.rsvqa.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
class ProviderPricingCatalog {

    record Estimate(BigDecimal costUsd, String state, String version) {
    }

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final ProviderPolicyProperties.Pricing pricing;

    ProviderPricingCatalog(ProviderPolicyProperties properties) {
        this.pricing = properties.pricing();
    }

    Estimate estimate(
            String providerId,
            String modelId,
            Integer promptTokens,
            Integer completionTokens
    ) {
        ProviderPolicyProperties.Price price = pricing.models().get(key(providerId, modelId));
        if (price == null || promptTokens == null || completionTokens == null) {
            return new Estimate(null, "UNKNOWN", pricing.version());
        }
        try {
            BigDecimal input = new BigDecimal(price.inputUsdPerMillion());
            BigDecimal output = new BigDecimal(price.outputUsdPerMillion());
            if (input.signum() < 0 || output.signum() < 0) {
                return new Estimate(null, "UNKNOWN", pricing.version());
            }
            BigDecimal cost = input.multiply(BigDecimal.valueOf(promptTokens))
                    .add(output.multiply(BigDecimal.valueOf(completionTokens)))
                    .divide(MILLION, 8, RoundingMode.HALF_UP);
            return new Estimate(cost, "KNOWN", pricing.version());
        } catch (NumberFormatException error) {
            return new Estimate(null, "UNKNOWN", pricing.version());
        }
    }

    Map<String, String> metadata(String providerId, String modelId) {
        ProviderPolicyProperties.Price price = pricing.models().get(key(providerId, modelId));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("pricingVersion", pricing.version());
        if (price == null || !valid(price.inputUsdPerMillion()) || !valid(price.outputUsdPerMillion())) {
            metadata.put("pricingState", "UNKNOWN");
            metadata.put("estimatedCost", "unknown");
        } else {
            metadata.put("pricingState", "KNOWN");
            metadata.put("inputUsdPerMillionTokens", price.inputUsdPerMillion());
            metadata.put("outputUsdPerMillionTokens", price.outputUsdPerMillion());
        }
        return Map.copyOf(metadata);
    }

    private static boolean valid(String value) {
        try {
            return value != null && !value.isBlank() && new BigDecimal(value).signum() >= 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String key(String providerId, String modelId) {
        return ((providerId == null ? "" : providerId) + "/" + (modelId == null ? "" : modelId))
                .toLowerCase(java.util.Locale.ROOT);
    }
}
