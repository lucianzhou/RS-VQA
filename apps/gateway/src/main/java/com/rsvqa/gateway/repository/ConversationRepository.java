package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ConversationEntity;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {
    List<ConversationEntity> findByProjectIdAndArchivedFalseOrderByUpdatedAtDesc(UUID projectId);
    List<ConversationEntity> findByProjectIdOrderByUpdatedAtDesc(UUID projectId);
    List<ConversationEntity> findByProjectUserIdAndArchivedTrueOrderByUpdatedAtDesc(UUID userId);
    Optional<ConversationEntity> findByIdAndProjectUserId(UUID id, UUID userId);
}
