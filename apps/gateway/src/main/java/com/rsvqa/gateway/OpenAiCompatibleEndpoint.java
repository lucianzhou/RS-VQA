package com.rsvqa.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import io.micrometer.observation.ObservationRegistry;

/**
 * Shared plumbing for every OpenAI-compatible vision endpoint the gateway talks
 * to (DashScope today, the Gemini relay now).
 *
 * <p>It exists so that adding a second compatible vendor does not mean a second
 * copy of the same model construction, timeout, retry, usage-extraction and
 * boundary-prompt code. Vendors differ only in configuration and in the wording
 * of their boundary prompt, so those are parameters, not subclasses.
 */
final class OpenAiCompatibleEndpoint {

    private OpenAiCompatibleEndpoint() {
    }

    record Endpoint(String baseUrl, String apiKey, String completionsPath) {
    }

    record Tuning(
            String model,
            double temperature,
            int maxOutputTokens,
            int timeoutSeconds,
            int maxRetries
    ) {
    }

    /**
     * Builds a chat model bound to {@code endpoint}.
     *
     * <p>Timeouts are enforced on the HTTP client, not merely advertised: a relay
     * that accepts a connection and never answers must fail on its own rather
     * than occupying a request thread indefinitely.
     */
    static OpenAiChatModel chatModel(
            Endpoint endpoint,
            Tuning tuning,
            ObservationRegistry observationRegistry
    ) {
        Duration timeout = Duration.ofSeconds(tuning.timeoutSeconds());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.min(15, tuning.timeoutSeconds())));
        requestFactory.setReadTimeout(timeout);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .completionsPath(endpoint.completionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder())
                .responseErrorHandler(new RelayResponseErrorHandler(tuning.model()))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(tuning.model())
                .temperature(tuning.temperature())
                .maxTokens(tuning.maxOutputTokens())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(retryTemplate(tuning.maxRetries()))
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * Retries only what is worth retrying.
     *
     * <p>{@link RelayResponseErrorHandler} maps 408/429/5xx to
     * {@link TransientAiException} and every other 4xx to
     * {@link NonTransientAiException}. Retrying an invalid key or an unknown
     * model just multiplies the failure, so those are surfaced immediately.
     */
    static RetryTemplate retryTemplate(int maxRetries) {
        return RetryTemplate.builder()
                .maxAttempts(maxRetries + 1)
                // IOException is listed because a read timeout does not arrive as
                // ResourceAccessException: RestClient reports it as a generic
                // RestClientException whose cause chain ends in SocketTimeoutException.
                // traversingCauses() is what makes that match.
                .retryOn(List.of(
                        TransientAiException.class, ResourceAccessException.class, IOException.class))
                .traversingCauses()
                .exponentialBackoff(Duration.ofMillis(400), 2.0, Duration.ofSeconds(5))
                .build();
    }

    /**
     * True when a failure is a transport problem (timeout, reset, DNS) rather
     * than a response the relay actually produced.
     *
     * <p>Classifying by cause chain instead of by wrapper type, because the
     * wrapper for a socket timeout is an ordinary {@link RestClientException}.
     */
    static boolean transportFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Single-turn image + question call, returning a provenance-carrying result. */
    static AiProvider.ProviderResult callVision(
            OpenAiChatModel chatModel,
            AiProvider.ProviderRequest request,
            String systemBoundary,
            String providerId,
            String modelId,
            String vendorLabel
    ) {
        if (request.image() == null || request.image().length == 0) {
            throw new RequestValidationException(vendorLabel + " 视觉调用需要图像。");
        }
        MimeType mimeType = MimeTypeUtils.parseMimeType(request.imageMimeType());
        UserMessage message = UserMessage.builder()
                .text(systemBoundary + "\n\n用户问题：" + request.prompt())
                .media(List.of(new Media(mimeType, new ByteArrayResource(request.image()))))
                .build();

        long started = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(List.of(message)));
        long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);

        String text = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new ModelServiceException(vendorLabel + " 未返回可展示的文本结果。");
        }

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String responseId = response.getMetadata() == null ? null : response.getMetadata().getId();
        return new AiProvider.ProviderResult(
                text.trim(),
                "EXTERNAL_VLM",
                providerId,
                modelId,
                responseId == null || responseId.isBlank() ? UUID.randomUUID().toString() : responseId,
                latencyMs,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                null
        );
    }

    /** Cost metadata shared by pay-per-use external providers. */
    static Map<String, String> payPerUseCostMetadata(String billing) {
        return Map.of(
                "billing", billing,
                "usage", "token_counts_recorded_when_available",
                "estimatedCost", "not_calculated_without_versioned_price_table"
        );
    }
}
