package com.rsvqa.gateway;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import io.micrometer.observation.ObservationRegistry;

/**
 * External general-purpose vision model served through an OpenAI-compatible
 * Gemini relay.
 *
 * <p>This provider makes no claim that any particular relay model can see. The
 * relay's public documentation lists {@code gemini-3.6-flash} without a
 * multimodal marker, so the vision model ID must be set explicitly by an
 * operator who has verified it. With no model configured the provider reports
 * {@code UNCONFIGURED} and the UI must not present external vision as available.
 *
 * <p>If a configured model turns out to reject image input, the provider latches
 * to {@code UNAVAILABLE} rather than continuing to advertise a capability the
 * endpoint does not have.
 */
@Component
public class GeminiRelayVisionProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiRelayVisionProvider.class);

    static final String PROVIDER_ID = "gemini";

    private static final String SYSTEM_BOUNDARY = """
            你是 RS-VQA 系统中的外部通用视觉辅助模型。
            你的输出必须明确属于外部通用视觉模型，不属于论文研究模型的预测。
            只根据当前提供的图像与用户问题回答；不伪造传感器、时间、坐标或地理位置。
            无法从图像可靠判断时必须说明不确定性，不要把推测写成事实。
            回答使用简洁中文，不要声称替代专业遥感解译或现场核验。
            """;

    private final GeminiRelayProperties properties;
    private final OpenAiChatModel chatModel;
    /** Set once the endpoint proves it cannot honour the vision contract. */
    private final AtomicReference<String> contractFailure = new AtomicReference<>();

    public GeminiRelayVisionProvider(
            GeminiRelayProperties properties,
            ObservationRegistry observationRegistry
    ) {
        this.properties = properties;
        this.chatModel = properties.visionConfigured()
                ? OpenAiCompatibleEndpoint.chatModel(
                        new OpenAiCompatibleEndpoint.Endpoint(
                                properties.baseUrl(), properties.apiKey(), properties.completionsPath()),
                        new OpenAiCompatibleEndpoint.Tuning(
                                properties.vision().model(),
                                properties.vision().temperature(),
                                properties.vision().maxOutputTokens(),
                                properties.vision().timeoutSeconds(),
                                properties.vision().maxRetries()),
                        observationRegistry)
                : null;
        if (this.chatModel != null) {
            // Model ID only. The base URL and key never reach a log line.
            log.info("Gemini relay vision role enabled with model {}", properties.vision().model());
        }
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                PROVIDER_ID,
                properties.vision().model().isBlank() ? "未配置" : properties.vision().model(),
                geminiDisplayName(properties.vision().model()),
                "EXTERNAL_VLM",
                configurationState(),
                Set.of("open_visual_question_answering", "image_description", "reasoned_explanation"),
                true,
                true,
                false,
                true,
                Duration.ofSeconds(properties.vision().timeoutSeconds()),
                properties.vision().maxRetries(),
                OpenAiCompatibleEndpoint.payPerUseCostMetadata("relay_pay_per_use")
        );
    }

    private static String geminiDisplayName(String model) {
        if (model == null || model.isBlank()) {
            return "Gemini";
        }
        return model.startsWith("gemini-") ? "Gemini-" + model.substring("gemini-".length()) : model;
    }

    String configurationState() {
        if (!properties.visionConfigured()) {
            return "UNCONFIGURED";
        }
        return contractFailure.get() == null ? "CONFIGURED" : "UNAVAILABLE";
    }

    @Override
    public ProviderResult invoke(ProviderRequest request) {
        if (chatModel == null) {
            throw new ProviderNotConfiguredException(
                    properties.unconfiguredReason(properties.vision(), "外部视觉模型角色"));
        }
        String latched = contractFailure.get();
        if (latched != null) {
            throw new ProviderNotConfiguredException(
                    "外部视觉模型当前不可用：" + latched + " 请改用研究模型，或在验证其他多模态模型后重新配置。");
        }

        try {
            ProviderResult result = OpenAiCompatibleEndpoint.callVision(
                    chatModel, request, SYSTEM_BOUNDARY, PROVIDER_ID,
                    properties.vision().model(), "外部通用视觉模型");
            log.info(
                    "provider={} model={} requestId={} latencyMs={} promptTokens={} completionTokens={} totalTokens={}",
                    PROVIDER_ID, properties.vision().model(), result.requestId(), result.latencyMs(),
                    result.promptTokens(), result.completionTokens(), result.totalTokens());
            return result;
        } catch (NonTransientAiException error) {
            // 4xx other than rate limiting: wrong model, no image support, bad key.
            // Retrying cannot help, and continuing to advertise vision would be a lie.
            String summary = summarize(error);
            contractFailure.compareAndSet(null, summary);
            log.warn("provider={} model={} failureType=contract_incompatible detail={}",
                    PROVIDER_ID, properties.vision().model(), summary);
            throw new ProviderNotConfiguredException("外部视觉模型未通过契约校验：" + summary);
        } catch (TransientAiException | ResourceAccessException error) {
            log.warn("provider={} model={} failureType=transient detail={}",
                    PROVIDER_ID, properties.vision().model(), error.getMessage());
            throw new ModelServiceException("外部视觉模型暂时不可用，请稍后重试。", error);
        } catch (ModelServiceException | RequestValidationException error) {
            throw error;
        } catch (RuntimeException error) {
            if (OpenAiCompatibleEndpoint.transportFailure(error)) {
                // A read timeout arrives as a plain RestClientException, so it is
                // classified by cause rather than by wrapper type.
                log.warn("provider={} model={} failureType=transport detail={}",
                        PROVIDER_ID, properties.vision().model(), error.getMessage());
                throw new ModelServiceException("外部视觉模型响应超时或连接中断，请稍后重试。", error);
            }
            log.warn("provider={} model={} failureType=malformed_response detail={}",
                    PROVIDER_ID, properties.vision().model(), error.getMessage());
            throw new ModelServiceException("外部视觉模型返回了无法解析的响应。", error);
        }
    }

    /** Trims a provider error to something safe and short enough to display. */
    private static String summarize(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String collapsed = message.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }
}
