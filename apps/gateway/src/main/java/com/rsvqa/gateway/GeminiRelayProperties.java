package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for an OpenAI-compatible Gemini relay endpoint.
 *
 * <p>The relay is addressed purely by configuration: there is no compiled-in
 * host and no dependency on Google's official API endpoint. Both the base URL
 * and the key are server-side only and are never placed in a descriptor, log
 * line or response DTO.
 *
 * <p>Two roles share the endpoint but are configured independently, because
 * "this relay can do text and tools" and "this relay's model can actually see
 * images" are separate claims that need separate evidence:
 *
 * <ul>
 *   <li>{@code agent} — RS-Bot's text and tool-calling model.</li>
 *   <li>{@code vision} — the external general-purpose vision VQA model.</li>
 * </ul>
 *
 * <p>{@code vision.model} deliberately has no default. A relay model is only
 * offered for image work once someone has set it explicitly, so an unverified
 * model can never drift into the vision slot.
 */
@ConfigurationProperties(prefix = "rsvqa.gemini")
public record GeminiRelayProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String completionsPath,
        Role vision,
        Role agent
) {

    public static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    public GeminiRelayProperties {
        baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        apiKey = apiKey == null ? "" : apiKey.trim();
        completionsPath = completionsPath == null || completionsPath.isBlank()
                ? DEFAULT_COMPLETIONS_PATH
                : completionsPath.trim();
        vision = vision == null ? Role.disabled() : vision;
        agent = agent == null ? Role.disabled() : agent;
    }

    /** Per-role model selection and limits. */
    public record Role(
            boolean enabled,
            String model,
            int timeoutSeconds,
            int maxRetries,
            int maxOutputTokens,
            double temperature
    ) {
        public Role {
            model = model == null ? "" : model.trim();
            timeoutSeconds = clamp(timeoutSeconds, 5, 180, 60);
            maxRetries = clamp(maxRetries, 0, 4, 2);
            maxOutputTokens = clamp(maxOutputTokens, 128, 8192, 1024);
            temperature = temperature < 0.0 ? 0.0 : Math.min(temperature, 2.0);
        }

        static Role disabled() {
            return new Role(false, "", 60, 2, 1024, 0.2);
        }

        boolean usable() {
            return enabled && !model.isBlank();
        }

        private static int clamp(int value, int min, int max, int fallback) {
            if (value <= 0) {
                return fallback;
            }
            return Math.max(min, Math.min(value, max));
        }
    }

    /** True when the shared endpoint itself is usable, regardless of role. */
    boolean endpointConfigured() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank();
    }

    boolean visionConfigured() {
        return endpointConfigured() && vision.usable();
    }

    boolean agentConfigured() {
        return endpointConfigured() && agent.usable();
    }

    /**
     * Why the relay is unusable, in terms safe to show a user.
     *
     * <p>Never mentions the base URL or the key, only which switch is missing.
     */
    String unconfiguredReason(Role role, String roleLabel) {
        if (!enabled) {
            return "Gemini 中转站未启用；请设置 RSVQA_GEMINI_ENABLED=true。";
        }
        if (baseUrl.isBlank()) {
            return "Gemini 中转站未配置 base URL；请设置 RSVQA_GEMINI_BASE_URL。";
        }
        if (apiKey.isBlank()) {
            return "Gemini 中转站未配置服务端密钥；请设置 RSVQA_GEMINI_API_KEY。浏览器登录状态不会被当作 API 授权。";
        }
        if (!role.enabled()) {
            return roleLabel + "未启用。";
        }
        return roleLabel + "未指定模型 ID；该角色不会回退到任何默认模型。";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
