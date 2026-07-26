package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentRunRepository;
import com.rsvqa.gateway.repository.ToolInvocationRepository;
import com.rsvqa.gateway.repository.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({TrustedAgentService.class, TrustedAgentTransactionTest.Support.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TrustedAgentTransactionTest {

    @Autowired
    TrustedAgentService service;

    @Autowired
    UserRepository users;

    @Autowired
    AgentRunRepository runs;

    @Autowired
    ToolInvocationRepository toolInvocations;

    @MockBean
    AgentSessionService agentSessions;

    @MockBean
    TrustedAgentTools trustedAgentTools;

    @MockBean
    AgentToolRegistry toolRegistry;

    @MockBean
    RsBotPlanner planner;

    @BeforeEach
    void authenticate() {
        users.save(new UserEntity("transaction-review", "Transaction Review", "USER", false));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "transaction-review", "n/a", java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsCompletedToolTraceAndFailedRunWhenPlanningLaterCrashes() {
        when(planner.available()).thenReturn(true);
        when(toolRegistry.callbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<RsBotPlanner.ExecutedTool> observer = invocation.getArgument(3);
            observer.accept(new RsBotPlanner.ExecutedTool(
                    "project_summary",
                    "{}",
                    "{\"questionCount\":42}",
                    "COMPLETED",
                    12,
                    null
            ));
            throw new ModelServiceException("relay disconnected");
        }).when(planner).plan(any(), any(), any(), any());

        var request = new AgentDtos.AgentRequest(
                null, null, null, null, "汇总当前状态", null);

        assertThatThrownBy(() -> service.run(request))
                .isInstanceOf(ModelServiceException.class);

        assertThat(runs.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo("FAILED");
            assertThat(run.getErrorCode()).isEqualTo("AGENT_PLANNING_FAILED");
        });
        assertThat(toolInvocations.findAll()).singleElement().satisfies(tool -> {
            assertThat(tool.getToolName()).isEqualTo("project_summary");
            assertThat(tool.getStatus()).isEqualTo("COMPLETED");
            assertThat(tool.getOutputSummary()).contains("questionCount");
        });
    }

    @TestConfiguration
    static class Support {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
