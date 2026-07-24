package com.rsvqa.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class UnconfiguredExternalVisionProvider implements AiProvider {

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                "external-vlm",
                "not-selected",
                "外部通用视觉模型",
                "EXTERNAL_VLM",
                "UNCONFIGURED",
                Set.of("open_visual_question_answering", "image_description"),
                true,
                true,
                true,
                true,
                Duration.ofSeconds(60),
                2,
                Map.of("state", "unknown_until_provider_selection")
        );
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        throw new ProviderNotConfiguredException(
                "外部视觉模型尚未配置。网页登录会员不会被当作 API 授权。"
        );
    }
}
