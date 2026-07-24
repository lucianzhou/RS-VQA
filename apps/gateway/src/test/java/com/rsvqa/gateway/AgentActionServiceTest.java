package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.AgentActionProposalEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentActionProposalRepository;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.ReportRepository;
import com.rsvqa.gateway.repository.UserRepository;

class AgentActionServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final BatchJobRepository batchJobs = mock(BatchJobRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final AgentActionProposalRepository proposals = mock(AgentActionProposalRepository.class);
    private final AuditEventRepository audit = mock(AuditEventRepository.class);
    private final AgentSessionService sessions = mock(AgentSessionService.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final BatchService batches = mock(BatchService.class);
    private final BatchWorker worker = mock(BatchWorker.class);
    private final ReportService reports = mock(ReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentActionService service = new AgentActionService(
            users, projects, conversations, batchJobs, reportRepository, proposals, audit, sessions,
            workspace, batches, worker, reports, objectMapper
    );
    private UserEntity user;
    private UUID userId;

    @BeforeEach
    void authenticate() {
        user = mock(UserEntity.class);
        userId = UUID.randomUUID();
        when(user.getId()).thenReturn(userId);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmationExecutesAnArchiveOnlyOnce() {
        UUID proposalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AgentActionProposalEntity proposal = proposal(
                "archive_project", "{\"projectId\":\"" + projectId + "\"}"
        );
        when(proposals.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.of(proposal));

        var first = service.confirm(proposalId);
        var repeated = service.confirm(proposalId);

        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(repeated.resultJson()).isEqualTo(first.resultJson());
        verify(workspace).archiveProject(projectId);
    }

    @Test
    void rejectionPreventsLaterExecution() {
        UUID proposalId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        AgentActionProposalEntity proposal = proposal(
                "archive_conversation", "{\"conversationId\":\"" + conversationId + "\"}"
        );
        when(proposals.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.of(proposal));

        assertThat(service.reject(proposalId).status()).isEqualTo("REJECTED");
        assertThat(service.confirm(proposalId).status()).isEqualTo("REJECTED");
        verify(workspace, never()).archiveConversation(conversationId);
    }

    @Test
    void anotherUsersProposalIsNotVisibleForConfirmation() {
        UUID proposalId = UUID.randomUUID();
        when(proposals.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(proposalId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("操作提案不存在。");
        verify(workspace, never()).archiveProject(org.mockito.ArgumentMatchers.any());
    }

    private AgentActionProposalEntity proposal(String action, String arguments) {
        return new AgentActionProposalEntity(
                user, null, action, arguments, "测试操作", UUID.randomUUID().toString(),
                Instant.now().plusSeconds(900)
        );
    }
}
