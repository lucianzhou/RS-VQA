package com.rsvqa.gateway;

import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import io.micrometer.observation.ObservationRegistry;

/**
 * Server-side Qwen3-VL adapter via the DashScope OpenAI-compatible endpoint.
 * The API key is read only from process configuration and is never exposed by
 * descriptors, logs, or response DTOs.
 */
@Component
public class QwenVisionProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(QwenVisionProvider.class);

    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";

    private static final String SYSTEM_BOUNDARY = """
            你是 RS-VQA 系统中的外部通用视觉辅助模型（Qwen3-VL）。
            你的输出必须明确属于外部 Qwen3-VL，不属于论文研究模型的预测。
            只根据当前提供的图像与用户问题回答；不伪造传感器、时间、坐标或地理位置。
            无法从图像可靠判断时必须说明不确定性，不要把推测写成事实。
            回答使用简洁中文，不要声称替代专业遥感解译或现场核验。
            """;

    private final QwenProviderProperties properties;
    private final OpenAiChatModel chatModel;

    public QwenVisionProvider(QwenProviderProperties properties, ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.chatModel = properties.configured()
                ? OpenAiCompatibleEndpoint.chatModel(
                        new OpenAiCompatibleEndpoint.Endpoint(
                                DASHSCOPE_BASE_URL, properties.apiKey(),
                                GeminiRelayProperties.DEFAULT_COMPLETIONS_PATH),
                        new OpenAiCompatibleEndpoint.Tuning(
                                properties.model(),
                                properties.temperature(),
                                properties.maxOutputTokens(),
                                properties.timeoutSeconds(),
                                properties.maxRetries()),
                        observationRegistry)
                : null;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                "qwen",
                properties.model(),
                "Qwen3-VL 32B",
                "EXTERNAL_VLM",
                properties.configured() ? "CONFIGURED" : "UNCONFIGURED",
                Set.of("open_visual_question_answering", "image_description", "reasoned_explanation"),
                true,
                true,
                false,
                true,
                Duration.ofSeconds(properties.timeoutSeconds()),
                properties.maxRetries(),
                OpenAiCompatibleEndpoint.payPerUseCostMetadata("dashscope_pay_per_use")
        );
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        if (chatModel == null) {
            throw new ProviderNotConfiguredException(
                    "Qwen3-VL 尚未配置。请设置 RSVQA_QWEN_ENABLED=true 并提供有效的 DASHSCOPE_API_KEY。"
            );
        }
        ProviderResult result = OpenAiCompatibleEndpoint.callVision(
                chatModel, request, SYSTEM_BOUNDARY, "qwen", properties.model(), "Qwen3-VL");
        log.info("provider=qwen model={} requestId={} latencyMs={} totalTokens={}",
                properties.model(), result.requestId(), result.latencyMs(), result.totalTokens());
        return result;
    }
}
