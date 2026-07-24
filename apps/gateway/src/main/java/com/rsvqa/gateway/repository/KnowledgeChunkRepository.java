package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.KnowledgeChunkEntity;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, UUID> {
    List<KnowledgeChunkEntity> findByDocumentIdOrderByOrdinalAsc(UUID documentId);
    void deleteByDocumentId(UUID documentId);
}
