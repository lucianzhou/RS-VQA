package com.rsvqa.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import io.micrometer.observation.ObservationRegistry;

/**
 * Server-side Qwen3-VL adapter via DashScope OpenAI-compatible endpoint.
 * The API key is read only from process configuration and is never exposed
 * by descriptors, logs, or response DTOs.
 */
@Component
public class QwenVisionProvider implements AiProvider {

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
        this.chatModel = properties.configured() ? buildModel(properties, observationRegistry) : null;
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
                Map.of(
                        "billing", "dashscope_pay_per_use",
                        "usage", "token_counts_recorded_when_available",
                        "estimatedCost", "not_calculated_without_versioned_price_table"
                )
        );
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        if (chatModel == null) {
            throw new ProviderNotConfiguredException(
                    "Qwen3-VL 尚未配置。请设置 RSVQA_QWEN_ENABLED=true 并提供有效的 DASHSCOPE_API_KEY。"
            );
        }
        if (request.image() == null || request.image().length == 0) {
            throw new RequestValidationException("Qwen3-VL 视觉调用需要图像。");
        }
        MimeType mimeType = MimeTypeUtils.parseMimeType(request.imageMimeType());
        UserMessage message = UserMessage.builder()
                .text(SYSTEM_BOUNDARY + "\n\n用户问题：" + request.prompt())
                .media(List.of(new Media(mimeType, new ByteArrayResource(request.image()))))
                .build();
        long started = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(List.of(message)));
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new ModelServiceException("Qwen3-VL 未返回可展示的文本结果。");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String responseId = response.getMetadata() == null ? null : response.getMetadata().getId();
        return new ProviderResult(
                response.getResult().getOutput().getText().trim(),
                "EXTERNAL_VLM",
                "qwen",
                properties.model(),
                responseId == null || responseId.isBlank() ? UUID.randomUUID().toString() : responseId,
                latencyMs,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                null
        );
    }

    private static OpenAiChatModel buildModel(
            QwenProviderProperties properties,
            ObservationRegistry observationRegistry
    ) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(properties.apiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxTokens(properties.maxOutputTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(properties.maxRetries() + 1).build())
                .observationRegistry(observationRegistry)
                .build();
    }
}
