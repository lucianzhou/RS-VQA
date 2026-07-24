package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.BatchJobEntity;

public interface BatchJobRepository extends JpaRepository<BatchJobEntity, UUID> {
    List<BatchJobEntity> findByUserIdAndArchivedFalseOrderByCreatedAtDesc(UUID userId);
    List<BatchJobEntity> findByUserIdAndArchivedTrueOrderByCreatedAtDesc(UUID userId);
    Optional<BatchJobEntity> findByIdAndUserId(UUID id, UUID userId);
}
