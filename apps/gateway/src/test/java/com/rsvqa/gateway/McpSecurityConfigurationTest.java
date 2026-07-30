package com.rsvqa.gateway;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

@WebMvcTest(McpSecurityTestController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = {
        "rsvqa.mcp.enabled=true",
        "rsvqa.mcp.bearer-token=0123456789abcdef0123456789abcdef",
        "rsvqa.mcp.principal=local-demo"
})
class McpSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private AuditEventRepository auditEvents;

    @Test
    void anonymousMcpInitializeIsRejected() throws Exception {
        mockMvc.perform(initialize())
                .andExpect(status().isUnauthorized());

        verify(auditEvents).save(argThat(event ->
                "MCP_REQUEST".equals(event.getEntityType())
                        && "FAILURE".equals(event.getOutcome())
                        && event.getUser() == null
        ));
    }

    @Test
    void wrongMcpBearerTokenIsRejected() throws Exception {
        mockMvc.perform(initialize().header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredMcpBearerTokenIsAcceptedWithoutCsrf() throws Exception {
        given(users.findByUsername("local-demo")).willReturn(java.util.Optional.of(
                new UserEntity("local-demo", "本地演示用户", "USER", true)
        ));

        mockMvc.perform(initialize().header(
                        "Authorization",
                        "Bearer 0123456789abcdef0123456789abcdef"
                ))
                .andExpect(status().isNoContent());

        verify(auditEvents, times(1)).save(argThat(event ->
                "MCP_REQUEST".equals(event.getEntityType())
                        && "SUCCESS".equals(event.getOutcome())
                        && event.getUser() != null
                        && "local-demo".equals(event.getUser().getUsername())
        ));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initialize() {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                        """);
    }
}
