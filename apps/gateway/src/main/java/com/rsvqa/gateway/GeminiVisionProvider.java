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
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;

import io.micrometer.observation.ObservationRegistry;

/**
 * Server-side Gemini adapter. The API key is read only from process
 * configuration and is never exposed by descriptors, logs, or response DTOs.
 */
@Component
public class GeminiVisionProvider implements AiProvider {

    private static final String SYSTEM_BOUNDARY = """
            你是 RS-VQA 系统中的外部通用视觉辅助模型。
            你的输出必须明确属于外部 Gemini，不属于论文研究模型的预测。
            只根据当前提供的图像与用户问题回答；不伪造传感器、时间、坐标或地理位置。
            无法从图像可靠判断时必须说明不确定性，不要把推测写成事实。
            回答使用简洁中文，不要声称替代专业遥感解译或现场核验。
            """;

    private final GeminiProviderProperties properties;
    private final GoogleGenAiChatModel chatModel;

    public GeminiVisionProvider(GeminiProviderProperties properties, ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.chatModel = properties.configured() ? buildModel(properties, observationRegistry) : null;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                "gemini",
                properties.model(),
                "Gemini 通用视觉助手",
                "EXTERNAL_VLM",
                properties.configured() ? "CONFIGURED" : "UNCONFIGURED",
                Set.of("open_visual_question_answering", "image_description", "reasoned_explanation"),
                true,
                true,
                true,
                true,
                Duration.ofSeconds(properties.timeoutSeconds()),
                properties.maxRetries(),
                Map.of(
                        "billing", "provider_managed",
                        "usage", "token_counts_recorded_when_available",
                        "estimatedCost", "not_calculated_without_versioned_price_table"
                )
        );
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        if (chatModel == null) {
            throw new ProviderNotConfiguredException(
                    "Gemini 尚未配置。Google AI Pro 网页会员不会被当作 API 授权。"
            );
        }
        if (request.image() == null || request.image().length == 0) {
            throw new RequestValidationException("Gemini 视觉调用需要图像。");
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
            throw new ModelServiceException("Gemini 未返回可展示的文本结果。");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String responseId = response.getMetadata() == null ? null : response.getMetadata().getId();
        return new ProviderResult(
                response.getResult().getOutput().getText().trim(),
                "EXTERNAL_VLM",
                "gemini",
                properties.model(),
                responseId == null || responseId.isBlank() ? UUID.randomUUID().toString() : responseId,
                latencyMs,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                null
        );
    }

    private static GoogleGenAiChatModel buildModel(
            GeminiProviderProperties properties,
            ObservationRegistry observationRegistry
    ) {
        HttpRetryOptions retry = HttpRetryOptions.builder()
                .attempts(properties.maxRetries() + 1)
                .initialDelay(0.5)
                .maxDelay(4.0)
                .expBase(2.0)
                .jitter(0.2)
                .httpStatusCodes(408, 429, 500, 502, 503, 504)
                .build();
        Client client = Client.builder()
                .apiKey(properties.apiKey())
                .httpOptions(HttpOptions.builder()
                        .timeout(properties.timeoutSeconds() * 1000)
                        .retryOptions(retry)
                        .build())
                .build();
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(properties.model())
                .temperature(properties.temperature())
                .maxOutputTokens(properties.maxOutputTokens())
                .candidateCount(1)
                .includeThoughts(false)
                .includeExtendedUsageMetadata(true)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .observationRegistry(observationRegistry)
                .build();
    }
}
