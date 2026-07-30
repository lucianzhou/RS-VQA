package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budgets for a single RS-Bot planning run.
 *
 * <p>Every limit here exists because an LLM tool loop has no natural stopping
 * point: without a step cap it can ping-pong between tools, without a token cap
 * a single question can cost arbitrarily much, and without a deadline a slow
 * relay holds the request open until the client gives up.
 */
@ConfigurationProperties(prefix = "rsvqa.rs-bot")
public record RsBotProperties(
        int maxToolSteps,
        int maxToolCallsPerStep,
        int maxTotalTokens,
        int timeoutSeconds,
        int maxToolOutputChars
) {

    /**
     * Bumped whenever the system prompt or the loop's contract changes, and
     * persisted with each run so a stored answer can be traced to the
     * instructions that produced it.
     */
    public static final String PROMPT_VERSION = "rs-bot/1.1.0";

    public RsBotProperties {
        maxToolSteps = clamp(maxToolSteps, 1, 12, 6);
        maxToolCallsPerStep = clamp(maxToolCallsPerStep, 1, 8, 4);
        maxTotalTokens = clamp(maxTotalTokens, 1_000, 200_000, 40_000);
        timeoutSeconds = clamp(timeoutSeconds, 10, 300, 120);
        maxToolOutputChars = clamp(maxToolOutputChars, 1_000, 200_000, 24_000);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(value, max));
    }
}
