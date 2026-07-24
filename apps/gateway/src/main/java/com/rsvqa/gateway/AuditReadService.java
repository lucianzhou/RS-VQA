package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
class AuditReadService {

    private final UserRepository users;
    private final AuditEventRepository events;

    AuditReadService(UserRepository users, AuditEventRepository events) {
        this.users = users;
        this.events = events;
    }

    @Transactional(readOnly = true)
    List<AuditEntry> lookup(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 120) {
            throw new RequestValidationException("审计检索词不能超过 120 个字符。");
        }
        UserEntity user = currentUser();
        return events.findTop100ByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(event -> normalized.isBlank() || "recent".equals(normalized) || matches(event, normalized))
                .limit(20)
                .map(AuditEntry::from)
                .toList();
    }

    private static boolean matches(AuditEventEntity event, String query) {
        return contains(event.getEventType(), query)
                || contains(event.getEntityType(), query)
                || contains(event.getTraceId(), query)
                || contains(event.getSummary(), query)
                || (event.getEntityId() != null && event.getEntityId().toString().equalsIgnoreCase(query));
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    record AuditEntry(
            UUID id,
            String eventType,
            String entityType,
            UUID entityId,
            String traceId,
            String outcome,
            String summary,
            Instant createdAt
    ) {
        static AuditEntry from(AuditEventEntity event) {
            return new AuditEntry(
                    event.getId(), event.getEventType(), event.getEntityType(), event.getEntityId(),
                    event.getTraceId(), event.getOutcome(), event.getSummary(), event.getCreatedAt()
            );
        }
    }
}
