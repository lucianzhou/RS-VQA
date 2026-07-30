package com.rsvqa.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    private final List<AiProvider> providers;
    private final VqaService vqa;
    private final ModelReleaseRegistry registry;
    private final ProviderReliabilityService reliability;

    public ProviderController(
            List<AiProvider> providers,
            VqaService vqa,
            ModelReleaseRegistry registry,
            ProviderReliabilityService reliability
    ) {
        this.providers = providers;
        this.vqa = vqa;
        this.registry = registry;
        this.reliability = reliability;
    }

    @GetMapping
    public List<AiProvider.ProviderDescriptor> list() {
        RuntimeModelInfoResponse model = vqa.currentModel();
        registry.record(model);
        AiProvider.ProviderDescriptor research = new AiProvider.ProviderDescriptor(
                "research-rsvqa",
                model.modelReleaseId() == null ? "none" : model.modelReleaseId(),
                "RS-VQA 研究模型",
                "RESEARCH_MODEL",
                model.ready() ? "CONFIGURED" : "UNAVAILABLE",
                Set.of("closed_set_presence", "closed_set_count", "closed_set_area", "closed_set_comparison"),
                true,
                false,
                false,
                true,
                Duration.ofSeconds(15),
                0,
                Map.of("billing", "self_hosted", "runtimeMode", model.mode())
        );
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(research),
                providers.stream().map(AiProvider::descriptor).map(reliability::decorate)
        ).toList();
    }
}
