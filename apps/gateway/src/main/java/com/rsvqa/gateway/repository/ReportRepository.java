package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ReportEntity;

public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {
    List<ReportEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    Optional<ReportEntity> findByIdAndUserId(UUID id, UUID userId);
}
