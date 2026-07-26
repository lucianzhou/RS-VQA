package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import io.micrometer.observation.ObservationRegistry;

/**
 * Contract tests for the OpenAI-compatible Gemini relay.
 *
 * <p>Everything here runs against a local {@link MockWebServer}. No test in this
 * class may reach a real relay or read a real key: the point is to pin the
 * request shape and the failure behaviour, both of which must hold before anyone
 * spends money on a live smoke.
 */
class GeminiRelayContractTest {

    private static final String SECRET_KEY = "relay-test-key-do-not-log";

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private GeminiRelayProperties properties(String visionModel, String agentModel, int maxRetries) {
        return new GeminiRelayProperties(
                true,
                server.url("/").toString(),
                SECRET_KEY,
                "/v1/chat/completions",
                new GeminiRelayProperties.Role(!visionModel.isBlank(), visionModel, 5, maxRetries, 1024, 0.2),
                new GeminiRelayProperties.Role(!agentModel.isBlank(), agentModel, 5, maxRetries, 2048, 0.2)
        );
    }

    private GeminiRelayVisionProvider vision(String model, int maxRetries) {
        return new GeminiRelayVisionProvider(properties(model, "", maxRetries), ObservationRegistry.NOOP);
    }

    private static MockResponse chatCompletion(String content) {
        return jsonResponse(200, """
                {"id":"chatcmpl-relay-1","object":"chat.completion","created":1,
                 "model":"relay-model",
                 "choices":[{"index":0,"finish_reason":"stop",
                   "message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":41,"completion_tokens":7,"total_tokens":48}}
                """.formatted(content));
    }

    private static MockResponse jsonResponse(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static AiProvider.ProviderRequest imageRequest() {
        return new AiProvider.ProviderRequest(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}, "image/png", "这张图里有什么？", "conversation-1");
    }

    // --- configuration -----------------------------------------------------

    @Test
    void visionStaysUnconfiguredUntilAModelIsNamedExplicitly() {
        // Endpoint, key and the vision switch are all present; only the model ID
        // is missing. The relay documents gemini-3.6-flash without a multimodal
        // marker, so nothing is assumed and no default is substituted.
        var properties = new GeminiRelayProperties(
                true, server.url("/").toString(), SECRET_KEY, null,
                new GeminiRelayProperties.Role(true, "", 60, 0, 1024, 0.2),
                GeminiRelayProperties.Role.disabled()
        );
        var provider = new GeminiRelayVisionProvider(properties, ObservationRegistry.NOOP);

        assertThat(provider.descriptor().configurationState()).isEqualTo("UNCONFIGURED");
        assertThatThrownBy(() -> provider.invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class)
                .hasMessageContaining("未指定模型 ID");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void aDisabledVisionRoleSaysSoRatherThanBlamingTheModel() {
        assertThatThrownBy(() -> vision("", 0).invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void agentAndVisionRolesAreConfiguredIndependently() {
        var properties = properties("", "gemini-3.6-flash", 0);

        var agent = new GeminiRelayAgentModel(properties, ObservationRegistry.NOOP);
        var visionProvider = new GeminiRelayVisionProvider(properties, ObservationRegistry.NOOP);

        assertThat(agent.available()).isTrue();
        assertThat(agent.modelId()).isEqualTo("gemini-3.6-flash");
        assertThat(visionProvider.descriptor().configurationState()).isEqualTo("UNCONFIGURED");
    }

    @Test
    void descriptorUsesTheConcreteGeminiModelName() {
        var descriptor = vision("gemini-3.6-flash", 0).descriptor();

        assertThat(descriptor.displayName()).isEqualTo("Gemini-3.6-flash");
        assertThat(descriptor.modelId()).isEqualTo("gemini-3.6-flash");
    }

    @Test
    void agentRoleFailsClosedWhenDisabled() {
        var agent = new GeminiRelayAgentModel(properties("", "", 0), ObservationRegistry.NOOP);

        assertThat(agent.available()).isFalse();
        assertThat(agent.configurationState()).isEqualTo("UNCONFIGURED");
        assertThatThrownBy(agent::chatModel).isInstanceOf(ProviderNotConfiguredException.class);
    }

    // --- request shape -----------------------------------------------------

    @Test
    void sendsImageAsBase64DataUrlToTheConfiguredCompletionsPath() throws Exception {
        server.enqueue(chatCompletion("图中可见道路与建筑。"));

        var result = vision("relay-vision-model", 0).invoke(imageRequest());

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer " + SECRET_KEY);
        String body = recorded.getBody().readString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"model\":\"relay-vision-model\"");
        assertThat(body).contains("image_url");
        assertThat(body).contains("data:image/png;base64,");
        assertThat(result.content()).isEqualTo("图中可见道路与建筑。");
        assertThat(result.providerId()).isEqualTo("gemini");
        assertThat(result.modelId()).isEqualTo("relay-vision-model");
        assertThat(result.sourceType()).isEqualTo("EXTERNAL_VLM");
    }

    @Test
    void recordsRequestIdLatencyAndTokenUsage() {
        server.enqueue(chatCompletion("可见建筑。"));

        var result = vision("relay-vision-model", 0).invoke(imageRequest());

        assertThat(result.requestId()).isEqualTo("chatcmpl-relay-1");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.promptTokens()).isEqualTo(41);
        assertThat(result.completionTokens()).isEqualTo(7);
        assertThat(result.totalTokens()).isEqualTo(48);
    }

    @Test
    void sendsTheBoundaryPromptSoTheExternalAnswerIsNeverPresentedAsResearchOutput() throws Exception {
        server.enqueue(chatCompletion("ok"));

        vision("relay-vision-model", 0).invoke(imageRequest());

        String body = server.takeRequest().getBody().readString(StandardCharsets.UTF_8);
        assertThat(body).contains("不属于论文研究模型的预测");
    }

    // --- text, structured output and tools (agent role) ---------------------

    @Test
    void agentRoleCompletesPlainTextChat() {
        server.enqueue(chatCompletion("已汇总。"));
        var agent = new GeminiRelayAgentModel(properties("", "gemini-3.6-flash", 0), ObservationRegistry.NOOP);

        ChatResponse response = agent.chatModel().call(new Prompt("汇总这个项目"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("已汇总。");
    }

    @Test
    void agentRoleParsesStructuredJsonContent() {
        server.enqueue(chatCompletion("{\\\"lowConfidence\\\": 3}"));
        var agent = new GeminiRelayAgentModel(properties("", "gemini-3.6-flash", 0), ObservationRegistry.NOOP);

        ChatResponse response = agent.chatModel().call(new Prompt("以 JSON 返回"));

        assertThat(response.getResult().getOutput().getText()).contains("lowConfidence");
    }

    @Test
    void agentRoleSurfacesToolCallsFromTheRelay() {
        server.enqueue(jsonResponse(200, """
                {"id":"chatcmpl-tool-1","object":"chat.completion","created":1,"model":"gemini-3.6-flash",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-1","type":"function",
                     "function":{"name":"project_summary","arguments":"{\\"projectId\\":\\"p-1\\"}"}}]}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """));
        var agent = new GeminiRelayAgentModel(properties("", "gemini-3.6-flash", 0), ObservationRegistry.NOOP);

        // Tool execution belongs to RS-Bot's own loop, not to Spring AI's
        // internal one, so this only asserts that the relay's tool_calls are
        // parsed and handed back.
        ChatResponse response = agent.chatModel().call(new Prompt("汇总这个项目",
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build()));

        assertThat(response.getResult().getOutput().getToolCalls())
                .extracting(org.springframework.ai.chat.messages.AssistantMessage.ToolCall::name)
                .containsExactly("project_summary");
    }

    @Test
    void streamingChunksAreAssembled() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"低"}}]}

                        data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"置信"}}]}

                        data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"案例"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """));
        var agent = new GeminiRelayAgentModel(properties("", "gemini-3.6-flash", 0), ObservationRegistry.NOOP);

        String assembled = agent.chatModel()
                .stream(new Prompt("列出低置信度案例"))
                .toStream()
                .map(chunk -> chunk.getResult() == null || chunk.getResult().getOutput() == null
                        ? "" : String.valueOf(chunk.getResult().getOutput().getText()))
                .reduce("", String::concat);

        assertThat(assembled).contains("低").contains("置信").contains("案例");
    }

    // --- failure modes -----------------------------------------------------

    @Test
    void rateLimitingIsRetriedAndThenSucceeds() throws Exception {
        server.enqueue(jsonResponse(429, "{\"error\":{\"message\":\"rate limited\"}}"));
        server.enqueue(chatCompletion("重试后成功。"));

        var result = vision("relay-vision-model", 2).invoke(imageRequest());

        assertThat(result.content()).isEqualTo("重试后成功。");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void serverErrorsAreRetriedUpToTheConfiguredBudget() {
        server.enqueue(jsonResponse(503, "{\"error\":{\"message\":\"upstream unavailable\"}}"));
        server.enqueue(jsonResponse(503, "{\"error\":{\"message\":\"upstream unavailable\"}}"));
        server.enqueue(jsonResponse(503, "{\"error\":{\"message\":\"upstream unavailable\"}}"));

        assertThatThrownBy(() -> vision("relay-vision-model", 2).invoke(imageRequest()))
                .isInstanceOf(ModelServiceException.class);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void invalidKeyIsNotRetriedAndLatchesTheProviderUnavailable() {
        server.enqueue(jsonResponse(401, "{\"error\":{\"message\":\"invalid api key\"}}"));
        var provider = vision("relay-vision-model", 3);

        assertThatThrownBy(() -> provider.invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(provider.descriptor().configurationState()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void unknownModelIsReportedAsAContractFailureRatherThanRetried() {
        server.enqueue(jsonResponse(404, "{\"error\":{\"message\":\"model not found\"}}"));
        var provider = vision("relay-vision-model", 3);

        assertThatThrownBy(() -> provider.invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class)
                .hasMessageContaining("契约校验");
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(provider.descriptor().configurationState()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void aModelThatRejectsImageInputStopsAdvertisingVision() {
        server.enqueue(jsonResponse(400,
                "{\"error\":{\"message\":\"this model does not support image input\"}}"));
        var provider = vision("gemini-3.6-flash", 2);

        assertThatThrownBy(() -> provider.invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class);

        assertThat(provider.descriptor().configurationState()).isEqualTo("UNAVAILABLE");
        // Subsequent calls short-circuit instead of burning more requests.
        assertThatThrownBy(() -> provider.invoke(imageRequest()))
                .isInstanceOf(ProviderNotConfiguredException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void readTimeoutIsEnforcedOnTheHttpClient() {
        // Uses the endpoint helper directly so the read timeout can be 1s; the
        // production Role clamp keeps a 5s floor, which would only slow the test.
        server.enqueue(chatCompletion("太慢了").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS));
        var model = OpenAiCompatibleEndpoint.chatModel(
                new OpenAiCompatibleEndpoint.Endpoint(server.url("/").toString(), SECRET_KEY, "/v1/chat/completions"),
                new OpenAiCompatibleEndpoint.Tuning("relay-vision-model", 0.2, 256, 1, 0),
                ObservationRegistry.NOOP);

        long started = System.nanoTime();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> model.call(new Prompt("hello")));

        assertThat(thrown).isNotNull();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_500);
        // A socket timeout is wrapped in a plain RestClientException, not in
        // ResourceAccessException, so it must be classified by cause chain.
        assertThat(OpenAiCompatibleEndpoint.transportFailure(thrown)).isTrue();
    }

    @Test
    void transportFailuresAreClassifiedByCauseNotByWrapperType() {
        var timeout = new org.springframework.web.client.RestClientException(
                "Error while extracting response", new java.net.SocketTimeoutException("Read timed out"));

        assertThat(OpenAiCompatibleEndpoint.transportFailure(timeout)).isTrue();
        assertThat(OpenAiCompatibleEndpoint.transportFailure(
                new org.springframework.ai.retry.NonTransientAiException("中转站返回 HTTP 401。"))).isFalse();
    }

    @Test
    void malformedResponseIsRejectedInsteadOfBeingShownToTheUser() {
        server.enqueue(jsonResponse(200, "{\"unexpected\":true}"));

        assertThatThrownBy(() -> vision("relay-vision-model", 0).invoke(imageRequest()))
                .isInstanceOf(ModelServiceException.class);
    }

    @Test
    void emptyContentIsRejected() {
        server.enqueue(chatCompletion(""));

        assertThatThrownBy(() -> vision("relay-vision-model", 0).invoke(imageRequest()))
                .isInstanceOf(ModelServiceException.class)
                .hasMessageContaining("未返回可展示的文本结果");
    }

    @Test
    void missingImageIsRejectedBeforeAnyRequestIsSent() {
        assertThatThrownBy(() -> vision("relay-vision-model", 0).invoke(
                new AiProvider.ProviderRequest(new byte[0], "image/png", "问题", null)))
                .isInstanceOf(RequestValidationException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    // --- secret hygiene ----------------------------------------------------

    @Test
    void neitherTheKeyNorTheBaseUrlAppearsInTheDescriptor() {
        var provider = vision("relay-vision-model", 0);

        String descriptor = provider.descriptor().toString();

        assertThat(descriptor).doesNotContain(SECRET_KEY);
        assertThat(descriptor).doesNotContain(server.url("/").host());
        assertThat(provider.descriptor().costMetadata().values()).noneMatch(v -> v.contains(SECRET_KEY));
    }

    @Test
    void errorMessagesShownToUsersNeverEchoTheKey() {
        server.enqueue(jsonResponse(401,
                "{\"error\":{\"message\":\"invalid api key " + SECRET_KEY + "\"}}"));
        var provider = vision("relay-vision-model", 0);

        // The relay echoed the key back; the provider must not pass that on.
        try {
            provider.invoke(imageRequest());
        } catch (RuntimeException error) {
            assertThat(error.getMessage()).doesNotContain(SECRET_KEY);
        }
    }

    @Test
    void unconfiguredReasonNamesTheSwitchNotTheEndpoint() {
        var properties = new GeminiRelayProperties(
                true, server.url("/").toString(), "", null,
                new GeminiRelayProperties.Role(true, "m", 60, 2, 1024, 0.2),
                GeminiRelayProperties.Role.disabled()
        );

        String reason = properties.unconfiguredReason(properties.vision(), "外部视觉模型角色");

        assertThat(reason).contains("RSVQA_GEMINI_API_KEY");
        assertThat(reason).doesNotContain(server.url("/").host());
    }

    // --- helper used by the media-encoding assertion -------------------------

    @Test
    void mediaIsAttachedWithTheDeclaredMimeType() {
        var message = UserMessage.builder()
                .text("t")
                .media(List.of(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(new byte[] {1}))))
                .build();

        assertThat(message.getMedia()).hasSize(1);
        assertThat(message.getMedia().get(0).getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
    }
}
