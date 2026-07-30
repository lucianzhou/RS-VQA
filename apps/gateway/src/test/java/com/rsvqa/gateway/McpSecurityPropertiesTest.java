package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpSecurityPropertiesTest {

    @Test
    void disabledMcpNeedsNoSecret() {
        assertThatCode(() -> new McpSecurityProperties(false, "", "").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void enabledMcpFailsClosedWithoutStrongTokenAndPrincipal() {
        assertThatThrownBy(() -> new McpSecurityProperties(true, "short", "local-demo").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSVQA_MCP_BEARER_TOKEN");
        assertThatThrownBy(() -> new McpSecurityProperties(
                true, "0123456789abcdef0123456789abcdef", ""
        ).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RSVQA_MCP_PRINCIPAL");
    }
}
