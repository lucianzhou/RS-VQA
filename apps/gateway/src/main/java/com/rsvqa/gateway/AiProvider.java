package com.rsvqa.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Vendor-neutral boundary for research and external visual-language providers.
 * Credentials are injected by provider-specific adapters and are never part of
 * this contract or its status response.
 */
public interface AiProvider {

    ProviderDescriptor descriptor();

    ProviderResult invoke(ProviderRequest request);

    record ProviderDescriptor(
            String providerId,
            String modelId,
            String displayName,
            String kind,
            String configurationState,
            Set<String> capabilities,
            boolean vision,
            boolean streaming,
            boolean toolCalling,
            boolean structuredOutput,
            Duration timeout,
            int maxRetries,
            Map<String, String> costMetadata
    ) {
    }

    record ProviderRequest(
            byte[] image,
            String imageMimeType,
            String prompt,
            String conversationId
    ) {
    }

    record ProviderResult(
            String content,
            String sourceType,
            String providerId,
            String modelId,
            String requestId,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Double estimatedCostUsd
    ) {
    }
}
