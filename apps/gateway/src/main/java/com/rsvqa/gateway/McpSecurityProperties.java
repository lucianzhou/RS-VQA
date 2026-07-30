package com.rsvqa.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rsvqa.mcp")
public record McpSecurityProperties(
        boolean enabled,
        String bearerToken,
        String principal
) {
    public McpSecurityProperties {
        bearerToken = bearerToken == null ? "" : bearerToken;
        principal = principal == null ? "" : principal.trim();
    }

    void validate() {
        if (!enabled) {
            return;
        }
        if (bearerToken.length() < 32) {
            throw new IllegalStateException(
                    "RSVQA_MCP_ENABLED requires RSVQA_MCP_BEARER_TOKEN with at least 32 characters."
            );
        }
        if (principal.isBlank()) {
            throw new IllegalStateException(
                    "RSVQA_MCP_ENABLED requires a non-blank RSVQA_MCP_PRINCIPAL."
            );
        }
    }

    @Override
    public String toString() {
        return "McpSecurityProperties[enabled=" + enabled + ", bearerToken=<redacted>, principal=" + principal + "]";
    }
}
