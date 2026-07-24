package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.KnowledgeDocumentEntity;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, UUID> {
    List<KnowledgeDocumentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<KnowledgeDocumentEntity> findByIdAndUserId(UUID id, UUID userId);
    Optional<KnowledgeDocumentEntity> findByUserIdAndSha256(UUID userId, String sha256);
}
