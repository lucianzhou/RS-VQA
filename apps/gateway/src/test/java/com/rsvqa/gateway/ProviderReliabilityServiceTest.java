package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ProviderReliabilityServiceTest {

    @Test
    void appliesAdmissionPerUserProviderAndWorkloadBeforeCallingTheRelay() {
        RecordingAdmissionStore admissions = new RecordingAdmissionStore();
        ProviderReliabilityService reliability = service(admissions, new MutableClock());
        AiProvider provider = successfulProvider("gemini", "gemini-3.6-flash");
        UUID userId = UUID.randomUUID();

        AiProvider.ProviderResult result = reliability.executeVision(
                userId, provider, imageRequest(), 1);

        assertThat(result.content()).isEqualTo("ok");
        assertThat(admissions.userId).isEqualTo(userId);
        assertThat(admissions.providerId).isEqualTo("gemini");
        assertThat(admissions.workload).isEqualTo(ProviderWorkload.VISION);
        assertThat(admissions.units).isEqualTo(1);
        assertThat(admissions.completedTokens).isEqualTo(30);
    }

    @Test
    void rejectsOversizedProviderWorkBeforeCallingTheRelay() {
        RecordingAdmissionStore admissions = new RecordingAdmissionStore();
        ProviderReliabilityService reliability = service(admissions, new MutableClock());
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("gemini", "m", request -> {
            calls.incrementAndGet();
            return result("gemini", "m", 10, 20);
        });

        assertThatThrownBy(() -> reliability.executeVision(
                UUID.randomUUID(), provider, imageRequest(), 2))
                .isInstanceOf(ProviderAdmissionException.class)
                .hasMessageContaining("单次");
        assertThat(calls).hasValue(0);
    }

    @Test
    void opensAfterTransientFailuresShortCircuitsAndRecoversThroughOneHalfOpenProbe() {
        MutableClock clock = new MutableClock();
        RecordingAdmissionStore admissions = new RecordingAdmissionStore();
        ProviderReliabilityService reliability = service(admissions, clock);
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("gemini", "m", request -> {
            if (calls.getAndIncrement() < 3) {
                throw new ModelServiceException("temporary");
            }
            return result("gemini", "m", 10, 20);
        });

        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> reliability.executeVision(
                    UUID.randomUUID(), provider, imageRequest(), 1))
                    .isInstanceOf(ModelServiceException.class);
        }
        assertThat(reliability.decorate(provider.descriptor()).configurationState())
                .isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> reliability.executeVision(
                UUID.randomUUID(), provider, imageRequest(), 1))
                .isInstanceOf(ProviderCircuitOpenException.class);
        assertThat(calls).hasValue(3);

        clock.advance(Duration.ofSeconds(31));
        AiProvider.ProviderResult recovered = reliability.executeVision(
                UUID.randomUUID(), provider, imageRequest(), 1);

        assertThat(recovered.content()).isEqualTo("ok");
        assertThat(calls).hasValue(4);
        assertThat(reliability.decorate(provider.descriptor()).configurationState())
                .isEqualTo("CONFIGURED");
    }

    @Test
    void permanentAndAdmissionFailuresDoNotTripTheCircuit() {
        MutableClock clock = new MutableClock();
        RecordingAdmissionStore admissions = new RecordingAdmissionStore();
        ProviderReliabilityService reliability = service(admissions, clock);
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("gemini", "m", request -> {
            calls.incrementAndGet();
            throw new ProviderNotConfiguredException("bad contract");
        });

        for (int index = 0; index < 4; index++) {
            assertThatThrownBy(() -> reliability.executeVision(
                    UUID.randomUUID(), provider, imageRequest(), 1))
                    .isInstanceOf(ProviderNotConfiguredException.class);
        }

        assertThat(calls).hasValue(4);
        assertThat(reliability.decorate(provider.descriptor()).configurationState())
                .isEqualTo("CONFIGURED");
    }

    @Test
    void admissionRejectionDoesNotPretendThatAHalfOpenProbeRecovered() {
        MutableClock clock = new MutableClock();
        RejectableAdmissionStore admissions = new RejectableAdmissionStore();
        ProviderReliabilityService reliability = service(admissions, clock);
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("gemini", "m", request -> {
            calls.incrementAndGet();
            throw new ModelServiceException("temporary");
        });

        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(() -> reliability.executeVision(
                    UUID.randomUUID(), provider, imageRequest(), 1))
                    .isInstanceOf(ModelServiceException.class);
        }
        clock.advance(Duration.ofSeconds(31));
        admissions.reject = true;

        assertThatThrownBy(() -> reliability.executeVision(
                UUID.randomUUID(), provider, imageRequest(), 1))
                .isInstanceOf(ProviderAdmissionException.class);

        assertThat(calls).hasValue(3);
        assertThat(reliability.decorate(provider.descriptor()).costMetadata())
                .containsEntry("circuitState", "HALF_OPEN_READY");
    }

    @Test
    void estimatesKnownPricesAndLeavesUnknownPricesNull() {
        ProviderPolicyProperties properties = properties();
        ProviderPricingCatalog pricing = new ProviderPricingCatalog(properties);

        ProviderPricingCatalog.Estimate known = pricing.estimate(
                "gemini", "gemini-3.6-flash", 1_000_000, 1_000_000);
        ProviderPricingCatalog.Estimate unknown = pricing.estimate(
                "gemini", "another-model", 10, 20);

        assertThat(known.costUsd()).hasToString("3.00000000");
        assertThat(known.state()).isEqualTo("KNOWN");
        assertThat(known.version()).isEqualTo("test-prices-1");
        assertThat(unknown.costUsd()).isNull();
        assertThat(unknown.state()).isEqualTo("UNKNOWN");
    }

    @Test
    void classifies408And429AsTransientWithoutTreating401Or403AsRecoverable() {
        assertThat(ProviderReliabilityService.transientFailure(
                new TransientAiException("中转站返回 HTTP 408。"))).isTrue();
        assertThat(ProviderReliabilityService.transientFailure(
                new TransientAiException("中转站返回 HTTP 429。"))).isTrue();
        assertThat(ProviderReliabilityService.transientFailure(
                new ProviderNotConfiguredException("HTTP 401"))).isFalse();
        assertThat(ProviderReliabilityService.transientFailure(
                new ProviderNotConfiguredException("HTTP 403"))).isFalse();
    }

    private static ProviderReliabilityService service(
            ProviderAdmissionStore admissions,
            MutableClock clock
    ) {
        ProviderPolicyProperties properties = properties();
        return new ProviderReliabilityService(
                properties,
                admissions,
                new ProviderPricingCatalog(properties),
                new SimpleMeterRegistry(),
                clock
        );
    }

    private static ProviderPolicyProperties properties() {
        return new ProviderPolicyProperties(
                true,
                new ProviderPolicyProperties.Limits(12, 2, 1, 100_000, 8_192),
                new ProviderPolicyProperties.Limits(20, 1, 1, 200_000, 40_000),
                new ProviderPolicyProperties.Circuit(3, Duration.ofSeconds(30)),
                new ProviderPolicyProperties.Pricing(
                        "test-prices-1",
                        Map.of(
                                "gemini/gemini-3.6-flash",
                                new ProviderPolicyProperties.Price("1.00", "2.00")
                        )
                )
        );
    }

    private static AiProvider successfulProvider(String providerId, String modelId) {
        return provider(providerId, modelId, request -> result(providerId, modelId, 10, 20));
    }

    private static AiProvider provider(
            String providerId,
            String modelId,
            java.util.function.Function<AiProvider.ProviderRequest, AiProvider.ProviderResult> invocation
    ) {
        return new AiProvider() {
            @Override
            public ProviderDescriptor descriptor() {
                return new ProviderDescriptor(
                        providerId, modelId, modelId, "EXTERNAL_VLM", "CONFIGURED",
                        Set.of("open_visual_question_answering"), true, true, false, true,
                        Duration.ofSeconds(10), 0, Map.of()
                );
            }

            @Override
            public ProviderResult invoke(ProviderRequest request) {
                return invocation.apply(request);
            }
        };
    }

    private static AiProvider.ProviderResult result(
            String providerId,
            String modelId,
            int promptTokens,
            int completionTokens
    ) {
        return new AiProvider.ProviderResult(
                "ok", "EXTERNAL_VLM", providerId, modelId, "request-1", 1,
                promptTokens, completionTokens, promptTokens + completionTokens, null
        );
    }

    private static AiProvider.ProviderRequest imageRequest() {
        return new AiProvider.ProviderRequest(new byte[] {1}, "image/png", "question", "conversation");
    }

    private static final class RecordingAdmissionStore implements ProviderAdmissionStore {
        private UUID userId;
        private String providerId;
        private ProviderWorkload workload;
        private int units;
        private Integer completedTokens;

        @Override
        public Admission acquire(
                UUID userId,
                String providerId,
                ProviderWorkload workload,
                int units,
                int tokenReservation,
                ProviderPolicyProperties.Limits limits
        ) {
            this.userId = userId;
            this.providerId = providerId;
            this.workload = workload;
            this.units = units;
            return actualTokens -> completedTokens = actualTokens;
        }
    }

    private static final class RejectableAdmissionStore implements ProviderAdmissionStore {
        private boolean reject;

        @Override
        public Admission acquire(
                UUID userId,
                String providerId,
                ProviderWorkload workload,
                int units,
                int tokenReservation,
                ProviderPolicyProperties.Limits limits
        ) {
            if (reject) {
                throw new ProviderAdmissionException("RATE_LIMIT", "rejected", 1);
            }
            return ignored -> {
            };
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-30T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
