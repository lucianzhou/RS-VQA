package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.rsvqa.gateway.domain.AgentActionProposalEntity;

import jakarta.persistence.LockModeType;

public interface AgentActionProposalRepository extends JpaRepository<AgentActionProposalEntity, UUID> {
    List<AgentActionProposalEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<AgentActionProposalEntity> findByUserIdAndAgentSessionIdOrderByCreatedAtDesc(UUID userId, UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentActionProposalEntity> findByIdAndUserId(UUID id, UUID userId);
}
