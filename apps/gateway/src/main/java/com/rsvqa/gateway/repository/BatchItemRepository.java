package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rsvqa.gateway.domain.BatchItemEntity;

import jakarta.persistence.LockModeType;

public interface BatchItemRepository extends JpaRepository<BatchItemEntity, UUID> {
    Optional<BatchItemEntity> findByIdAndBatchJobId(UUID id, UUID batchJobId);
    List<BatchItemEntity> findByBatchJobIdOrderByCreatedAtAsc(UUID jobId);
    List<BatchItemEntity> findByBatchJobIdAndStatusOrderByCreatedAtAsc(UUID jobId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item
            from BatchItemEntity item
            where item.id = :itemId
              and item.batchJob.id = :jobId
              and item.status = 'RUNNING'
              and item.leaseOwner = :leaseOwner
              and item.attemptCount = :attempt
            """)
    Optional<BatchItemEntity> lockOwnedRunning(
            @Param("jobId") UUID jobId,
            @Param("itemId") UUID itemId,
            @Param("leaseOwner") String leaseOwner,
            @Param("attempt") int attempt
    );
}
