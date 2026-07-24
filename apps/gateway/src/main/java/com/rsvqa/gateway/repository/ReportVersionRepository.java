package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ReportVersionEntity;

public interface ReportVersionRepository extends JpaRepository<ReportVersionEntity, UUID> {
    List<ReportVersionEntity> findByReportIdOrderByVersionNumberDesc(UUID reportId);
    Optional<ReportVersionEntity> findByReportIdAndVersionNumber(UUID reportId, int versionNumber);
}
