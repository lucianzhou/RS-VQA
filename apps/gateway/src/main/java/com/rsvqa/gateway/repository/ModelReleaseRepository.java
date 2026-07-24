package com.rsvqa.gateway.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rsvqa.gateway.domain.ModelReleaseEntity;

public interface ModelReleaseRepository extends JpaRepository<ModelReleaseEntity, UUID> {
    Optional<ModelReleaseEntity> findByModelReleaseId(String modelReleaseId);
}
