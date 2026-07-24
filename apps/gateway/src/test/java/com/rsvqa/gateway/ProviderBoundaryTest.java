package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderBoundaryTest {

    @Test
    void externalProviderIsExplicitlyUnconfiguredAndVendorNeutral() {
        var provider = new UnconfiguredExternalVisionProvider();

        assertThat(provider.descriptor().providerId()).isEqualTo("external-vlm");
        assertThat(provider.descriptor().configurationState()).isEqualTo("UNCONFIGURED");
        assertThatThrownBy(() -> provider.invoke(new AiProvider.ProviderRequest(
                new byte[0], "image/png", "describe", null
        ))).isInstanceOf(ProviderNotConfiguredException.class);
    }

    @Test
    void registryKeepsResearchAndExternalOriginsSeparate() {
        VqaService vqa = mock(VqaService.class);
        when(vqa.currentModel()).thenReturn(new RuntimeModelInfoResponse(
                "mock", true, "mock-demo-not-a-research-release", "1.0",
                "rsvqa_hr_grouped_answer_closed_set", "predicted_soft",
                "mock_demo", List.of("not research"), null
        ));

        var registry = mock(ModelReleaseRegistry.class);
        var descriptors = new ProviderController(List.of(new UnconfiguredExternalVisionProvider()), vqa, registry).list();

        assertThat(descriptors).extracting(AiProvider.ProviderDescriptor::kind)
                .containsExactly("RESEARCH_MODEL", "EXTERNAL_VLM");
    }
}
