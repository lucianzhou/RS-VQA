package com.rsvqa.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import io.micrometer.observation.ObservationRegistry;

/**
 * The relay's text and tool-calling role, kept separate from the vision role.
 *
 * <p>A relay model that handles chat and tool calls is not thereby a vision
 * model, and vice versa. Sharing one switch would let evidence for one capability
 * silently authorise the other, so each role has its own enable flag, model ID,
 * temperature, timeout and retry budget even though both use the same base URL
 * and key.
 *
 * <p>Exposed as a holder rather than a bare {@link ChatModel} bean so that
 * "configured" is a question callers must ask before they can call, instead of
 * discovering it through a null pointer.
 */
@Component
public class GeminiRelayAgentModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiRelayAgentModel.class);

    private final GeminiRelayProperties properties;
    private final OpenAiChatModel chatModel;

    public GeminiRelayAgentModel(
            GeminiRelayProperties properties,
            ObservationRegistry observationRegistry
    ) {
        this.properties = properties;
        this.chatModel = properties.agentConfigured()
                ? OpenAiCompatibleEndpoint.chatModel(
                        new OpenAiCompatibleEndpoint.Endpoint(
                                properties.baseUrl(), properties.apiKey(), properties.completionsPath()),
                        new OpenAiCompatibleEndpoint.Tuning(
                                properties.agent().model(),
                                properties.agent().temperature(),
                                properties.agent().maxOutputTokens(),
                                properties.agent().timeoutSeconds(),
                                properties.agent().maxRetries()),
                        observationRegistry)
                : null;
        if (this.chatModel != null) {
            log.info("Gemini relay agent role enabled with model {}", properties.agent().model());
        }
    }

    public boolean available() {
        return chatModel != null;
    }

    public String modelId() {
        return properties.agent().model();
    }

    public String configurationState() {
        return available() ? "CONFIGURED" : "UNCONFIGURED";
    }

    /** Reason the agent role is unusable, safe to display; never names the endpoint. */
    public String unavailableReason() {
        return properties.unconfiguredReason(properties.agent(), "RS-Bot 智能规划模型角色");
    }

    public ChatModel chatModel() {
        if (chatModel == null) {
            throw new ProviderNotConfiguredException(unavailableReason());
        }
        return chatModel;
    }
}
