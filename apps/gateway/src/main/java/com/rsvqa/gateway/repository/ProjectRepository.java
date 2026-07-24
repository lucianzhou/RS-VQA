package com.rsvqa.gateway.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ProjectEntity;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {
    List<ProjectEntity> findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(UUID userId);
    List<ProjectEntity> findByUserIdAndArchivedTrueOrderByUpdatedAtDesc(UUID userId);
    Optional<ProjectEntity> findByIdAndUserId(UUID id, UUID userId);
}
