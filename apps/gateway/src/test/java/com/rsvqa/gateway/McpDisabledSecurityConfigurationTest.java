package com.rsvqa.gateway;

import static org.mockito.ArgumentMatchers.argThat;
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

import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

@WebMvcTest(McpSecurityTestController.class)
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = "rsvqa.mcp.enabled=false")
class McpDisabledSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository users;

    @MockitoBean
    private AuditEventRepository auditEvents;

    @Test
    void disabledMcpRejectsAndAuditsInitialize() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isUnauthorized());

        verify(auditEvents).save(argThat(event ->
                "MCP_REQUEST".equals(event.getEntityType())
                        && "FAILURE".equals(event.getOutcome())
                        && event.getUser() == null
        ));
    }
}
