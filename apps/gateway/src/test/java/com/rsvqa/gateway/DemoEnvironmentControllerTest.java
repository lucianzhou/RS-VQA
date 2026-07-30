package com.rsvqa.gateway;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoEnvironmentController.class)
@Import(SecurityConfiguration.class)
class DemoEnvironmentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DemoEnvironmentService environment;

    @Test
    void resetRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(post("/api/v1/demo-environment/reset")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"RESET_LOCAL_DEMO\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetRequiresCsrfForAnAuthenticatedSession() throws Exception {
        mockMvc.perform(post("/api/v1/demo-environment/reset")
                        .with(user("local-demo").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"RESET_LOCAL_DEMO\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetReturnsTheSeededScope() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        given(environment.reset("RESET_LOCAL_DEMO")).willReturn(
                new DemoEnvironmentDtos.ResetResponse(
                        "INITIALIZING_RUNTIME_OUTPUTS",
                        projectId,
                        List.of(UUID.randomUUID()),
                        batchId,
                        List.of(UUID.randomUUID()),
                        24,
                        List.of("runtime")
                )
        );

        mockMvc.perform(post("/api/v1/demo-environment/reset")
                        .with(user("local-demo").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"RESET_LOCAL_DEMO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId.toString()))
                .andExpect(jsonPath("$.batchJobId").value(batchId.toString()))
                .andExpect(jsonPath("$.showcaseItems").value(24));
    }
}
