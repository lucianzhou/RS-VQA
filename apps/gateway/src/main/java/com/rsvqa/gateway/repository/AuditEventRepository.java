package com.rsvqa.gateway.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.AuditEventEntity;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    java.util.List<AuditEventEntity> findTop100ByUserIdOrderByCreatedAtDesc(UUID userId);
    java.util.List<AuditEventEntity> findTop200ByOrderByCreatedAtDesc();
}
