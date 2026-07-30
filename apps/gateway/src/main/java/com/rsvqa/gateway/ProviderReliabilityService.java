package com.rsvqa.gateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

@Service
class ProviderReliabilityService {

    record AgentExecution(RsBotPlanner.PlanResult plan, ProviderPricingCatalog.Estimate estimate) {
    }

    private final ProviderPolicyProperties properties;
    private final ProviderAdmissionStore admissions;
    private final ProviderPricingCatalog pricing;
    private final MeterRegistry metrics;
    private final ProviderCircuitRegistry circuits;

    @Autowired
    ProviderReliabilityService(
            ProviderPolicyProperties properties,
            ProviderAdmissionStore admissions,
            ProviderPricingCatalog pricing,
            MeterRegistry metrics
    ) {
        this(properties, admissions, pricing, metrics, Clock.systemUTC());
    }

    ProviderReliabilityService(
            ProviderPolicyProperties properties,
            ProviderAdmissionStore admissions,
            ProviderPricingCatalog pricing,
            MeterRegistry metrics,
            Clock clock
    ) {
        this.properties = properties;
        this.admissions = admissions;
        this.pricing = pricing;
        this.metrics = metrics;
        this.circuits = new ProviderCircuitRegistry(properties.circuit(), clock);
    }

    AiProvider.ProviderResult executeVision(
            UUID userId,
            AiProvider provider,
            AiProvider.ProviderRequest request,
            int items
    ) {
        AiProvider.ProviderDescriptor descriptor = provider.descriptor();
        ProviderCircuitRegistry.Key key = new ProviderCircuitRegistry.Key(
                descriptor.providerId(), descriptor.modelId(), ProviderWorkload.VISION);
        return execute(
                userId,
                key,
                items,
                properties.vision(),
                () -> provider.invoke(request),
                result -> result.totalTokens(),
                result -> {
                    ProviderPricingCatalog.Estimate estimate = pricing.estimate(
                            result.providerId(), result.modelId(),
                            result.promptTokens(), result.completionTokens());
                    return withCost(result, estimate.costUsd());
                }
        );
    }

    AgentExecution executeAgent(
            UUID userId,
            String modelId,
            Supplier<RsBotPlanner.PlanResult> call
    ) {
        ProviderCircuitRegistry.Key key = new ProviderCircuitRegistry.Key(
                GeminiRelayVisionProvider.PROVIDER_ID, modelId, ProviderWorkload.AGENT);
        RsBotPlanner.PlanResult plan = execute(
                userId,
                key,
                1,
                properties.agent(),
                call,
                RsBotPlanner.PlanResult::totalTokens,
                value -> value
        );
        ProviderPricingCatalog.Estimate estimate = pricing.estimate(
                key.providerId(), key.modelId(), plan.promptTokens(), plan.completionTokens());
        return new AgentExecution(plan, estimate);
    }

    AiProvider.ProviderDescriptor decorate(AiProvider.ProviderDescriptor descriptor) {
        if (!"EXTERNAL_VLM".equals(descriptor.kind())) {
            return descriptor;
        }
        ProviderCircuitRegistry.Key key = new ProviderCircuitRegistry.Key(
                descriptor.providerId(), descriptor.modelId(), ProviderWorkload.VISION);
        ProviderCircuitRegistry.State circuit = circuits.state(key);
        Map<String, String> metadata = new LinkedHashMap<>(descriptor.costMetadata());
        metadata.putAll(pricing.metadata(descriptor.providerId(), descriptor.modelId()));
        metadata.put("policy", properties.enabled() ? "ENFORCED" : "DISABLED");
        metadata.put("requestsPerMinute", Integer.toString(properties.vision().requestsPerMinute()));
        metadata.put("maxConcurrentPerUser", Integer.toString(properties.vision().maxConcurrent()));
        metadata.put("maxItemsPerRequest", Integer.toString(properties.vision().maxItemsPerRequest()));
        metadata.put("dailyTokenBudget", Long.toString(properties.vision().dailyTokenBudget()));
        metadata.put("circuitState", circuit.name());
        String state = descriptor.configurationState();
        if ("CONFIGURED".equals(state) && circuit == ProviderCircuitRegistry.State.OPEN) {
            state = "UNAVAILABLE";
        }
        return new AiProvider.ProviderDescriptor(
                descriptor.providerId(),
                descriptor.modelId(),
                descriptor.displayName(),
                descriptor.kind(),
                state,
                descriptor.capabilities(),
                descriptor.vision(),
                descriptor.streaming(),
                descriptor.toolCalling(),
                descriptor.structuredOutput(),
                descriptor.timeout(),
                descriptor.maxRetries(),
                Map.copyOf(metadata)
        );
    }

    private <T> T execute(
            UUID userId,
            ProviderCircuitRegistry.Key key,
            int items,
            ProviderPolicyProperties.Limits limits,
            Supplier<T> call,
            java.util.function.Function<T, Integer> totalTokens,
            java.util.function.Function<T, T> enrich
    ) {
        if (items < 1 || items > limits.maxItemsPerRequest()) {
            throw new ProviderAdmissionException(
                    "BATCH_SIZE",
                    "该模型单次最多处理 " + limits.maxItemsPerRequest() + " 个输入项。",
                    60
            );
        }
        ProviderCircuitRegistry.Ticket ticket = circuits.beforeCall(key);
        ProviderAdmissionStore.Admission admission = null;
        try {
            if (properties.enabled()) {
                admission = admissions.acquire(
                        userId, key.providerId(), key.workload(), items,
                        limits.tokenReservation(), limits);
            }
            T result = enrich.apply(call.get());
            if (admission != null) {
                admission.complete(totalTokens.apply(result));
            }
            circuits.success(ticket);
            counter("success", key).increment();
            return result;
        } catch (RuntimeException error) {
            if (admission != null) {
                admission.complete(0);
            }
            if (transientFailure(error)) {
                circuits.failure(ticket);
                counter("transient_failure", key).increment();
                if (!(error instanceof ModelServiceException)) {
                    throw new ModelServiceException("外部模型暂时不可用，请稍后重试。", error);
                }
            } else {
                if (error instanceof ProviderAdmissionException) {
                    circuits.cancelled(ticket);
                } else {
                    circuits.ignored(ticket);
                }
                counter(error instanceof ProviderAdmissionException ? "admission_rejected" : "permanent_failure", key)
                        .increment();
            }
            throw error;
        }
    }

    private io.micrometer.core.instrument.Counter counter(
            String outcome,
            ProviderCircuitRegistry.Key key
    ) {
        return metrics.counter(
                "rsvqa.provider.requests",
                "provider", key.providerId(),
                "workload", key.workload().name().toLowerCase(java.util.Locale.ROOT),
                "outcome", outcome
        );
    }

    static boolean transientFailure(Throwable error) {
        if (error instanceof ProviderAdmissionException
                || error instanceof ProviderCircuitOpenException
                || error instanceof ProviderNotConfiguredException
                || error instanceof RequestValidationException) {
            return false;
        }
        if (error instanceof ModelServiceException || error instanceof TransientAiException) {
            return true;
        }
        return OpenAiCompatibleEndpoint.transportFailure(error);
    }

    private static AiProvider.ProviderResult withCost(
            AiProvider.ProviderResult result,
            BigDecimal cost
    ) {
        return new AiProvider.ProviderResult(
                result.content(),
                result.sourceType(),
                result.providerId(),
                result.modelId(),
                result.requestId(),
                result.latencyMs(),
                result.promptTokens(),
                result.completionTokens(),
                result.totalTokens(),
                cost == null ? null : cost.doubleValue()
        );
    }
}
