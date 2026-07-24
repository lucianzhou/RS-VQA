package com.rsvqa.gateway.repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ImageAssetEntity;

public interface ImageAssetRepository extends JpaRepository<ImageAssetEntity, UUID> {
    Optional<ImageAssetEntity> findByConversationId(UUID conversationId);
    List<ImageAssetEntity> findByConversationProjectIdAndConversationArchivedFalseOrderByCreatedAtAsc(UUID projectId);
}
