package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.AgentSessionEntity;

public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity, UUID> {

    List<AgentSessionEntity> findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(UUID userId);

    Optional<AgentSessionEntity> findByIdAndUserId(UUID id, UUID userId);
}
