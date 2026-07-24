package com.rsvqa.gateway.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_event")
public class AuditEventEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "entity_type", length = 80)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "trace_id", nullable = false, length = 100)
    private String traceId;

    @Column(nullable = false, length = 40)
    private String outcome;

    @Column(columnDefinition = "TEXT")
    private String summary;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(UserEntity user, String eventType, String entityType, UUID entityId, String traceId, String outcome, String summary) {
        this.user = user;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.traceId = traceId;
        this.outcome = outcome;
        this.summary = summary;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getSummary() {
        return summary;
    }
}
