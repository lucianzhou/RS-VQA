package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.AgentRunEntity;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {

    List<AgentRunEntity> findByAgentSessionIdOrderByCreatedAtAsc(UUID agentSessionId);

    long countByAgentSessionId(UUID agentSessionId);
}
