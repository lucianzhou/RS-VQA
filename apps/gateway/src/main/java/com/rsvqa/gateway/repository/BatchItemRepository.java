package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.BatchItemEntity;

public interface BatchItemRepository extends JpaRepository<BatchItemEntity, UUID> {
    List<BatchItemEntity> findByBatchJobIdOrderByCreatedAtAsc(UUID jobId);
    List<BatchItemEntity> findByBatchJobIdAndStatusOrderByCreatedAtAsc(UUID jobId, String status);
}
