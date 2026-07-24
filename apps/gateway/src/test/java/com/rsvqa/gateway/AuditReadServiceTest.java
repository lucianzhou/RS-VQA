package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

class AuditReadServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AuditEventRepository events = mock(AuditEventRepository.class);
    private final AuditReadService service = new AuditReadService(users, events);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lookupIsRestrictedToTheCurrentUsersRecentEvents() {
        UUID userId = UUID.randomUUID();
        UserEntity user = mock(UserEntity.class);
        AuditEventEntity matching = mock(AuditEventEntity.class);
        AuditEventEntity other = mock(AuditEventEntity.class);
        when(user.getId()).thenReturn(userId);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(matching.getTraceId()).thenReturn("trace-123");
        when(matching.getEventType()).thenReturn("AGENT_TOOL_COMPLETED");
        when(other.getTraceId()).thenReturn("trace-999");
        when(other.getEventType()).thenReturn("LOGIN");
        when(events.findTop100ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(matching, other));

        List<AuditReadService.AuditEntry> result = service.lookup("trace-123");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().traceId()).isEqualTo("trace-123");
        verify(events).findTop100ByUserIdOrderByCreatedAtDesc(userId);
        verify(events, never()).findTop200ByOrderByCreatedAtDesc();
    }
}
