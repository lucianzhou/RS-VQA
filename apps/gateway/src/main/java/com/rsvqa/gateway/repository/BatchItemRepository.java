package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.BatchItemEntity;

public interface BatchItemRepository extends JpaRepository<BatchItemEntity, UUID> {
    Optional<BatchItemEntity> findByIdAndBatchJobId(UUID id, UUID batchJobId);
    List<BatchItemEntity> findByBatchJobIdOrderByCreatedAtAsc(UUID jobId);
    List<BatchItemEntity> findByBatchJobIdAndStatusOrderByCreatedAtAsc(UUID jobId, String status);
}
