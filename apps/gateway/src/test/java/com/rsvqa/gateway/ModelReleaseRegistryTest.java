package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.repository.ModelReleaseRepository;

class ModelReleaseRegistryTest {

    @Test
    void atomicallyUpsertsTheRuntimeRelease() {
        ModelReleaseRepository releases = Mockito.mock(ModelReleaseRepository.class);
        ModelReleaseRegistry registry = new ModelReleaseRegistry(releases, new ObjectMapper());
        RuntimeModelInfoResponse model = new RuntimeModelInfoResponse(
                "mock",
                true,
                "mock-demo-not-a-research-release",
                "1.0",
                "RSVQA-HR grouped 55-answer closed-set classification",
                "predicted_soft",
                "mock_demo",
                List.of("Mock only."),
                Map.of("prediction_origin", "mock_demo")
        );

        registry.record(model);

        verify(releases).upsert(
                any(UUID.class),
                eq("mock-demo-not-a-research-release"),
                eq("MOCK"),
                eq("mock"),
                eq("{\"prediction_origin\":\"mock_demo\"}"),
                eq(true),
                any(Instant.class)
        );
    }

    @Test
    void ignoresBlankReleaseIds() {
        ModelReleaseRepository releases = Mockito.mock(ModelReleaseRepository.class);
        ModelReleaseRegistry registry = new ModelReleaseRegistry(releases, new ObjectMapper());

        assertThatCode(() -> registry.record(new RuntimeModelInfoResponse(
                "mock", true, " ", "1.0", null, null, null, List.of(), Map.of()
        ))).doesNotThrowAnyException();

        verify(releases, never()).upsert(
                any(), any(), any(), any(), any(), eq(true), any()
        );
    }
}
