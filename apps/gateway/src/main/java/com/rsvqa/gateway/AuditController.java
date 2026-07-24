package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final UserRepository users;
    private final AuditEventRepository events;

    public AuditController(UserRepository users, AuditEventRepository events) {
        this.users = users;
        this.events = events;
    }

    @GetMapping("/me")
    public List<AuditEventResponse> mine() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        UUID userId = users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"))
                .getId();
        return events.findTop100ByUserIdOrderByCreatedAtDesc(userId).stream().map(AuditEventResponse::from).toList();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditEventResponse> admin() {
        return events.findTop200ByOrderByCreatedAtDesc().stream().map(AuditEventResponse::from).toList();
    }

    record AuditEventResponse(
            UUID id,
            String eventType,
            String entityType,
            UUID entityId,
            String traceId,
            String outcome,
            String summary,
            Instant createdAt
    ) {
        static AuditEventResponse from(AuditEventEntity event) {
            return new AuditEventResponse(
                    event.getId(), event.getEventType(), event.getEntityType(), event.getEntityId(),
                    event.getTraceId(), event.getOutcome(), event.getSummary(), event.getCreatedAt()
            );
        }
    }
}
