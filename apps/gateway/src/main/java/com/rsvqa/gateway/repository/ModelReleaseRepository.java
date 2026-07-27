package com.rsvqa.gateway.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rsvqa.gateway.domain.ModelReleaseEntity;

public interface ModelReleaseRepository extends JpaRepository<ModelReleaseEntity, UUID> {
    Optional<ModelReleaseEntity> findByModelReleaseId(String modelReleaseId);

    @Modifying
    @Query(value = """
            INSERT INTO model_release (
                id, model_release_id, provider_type, runtime_mode,
                manifest_json, ready, created_at, updated_at
            ) VALUES (
                :id, :modelReleaseId, :providerType, :runtimeMode,
                :manifestJson, :ready, :recordedAt, :recordedAt
            )
            ON CONFLICT (model_release_id) DO UPDATE SET
                provider_type = EXCLUDED.provider_type,
                runtime_mode = EXCLUDED.runtime_mode,
                manifest_json = EXCLUDED.manifest_json,
                ready = EXCLUDED.ready,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(
            @Param("id") UUID id,
            @Param("modelReleaseId") String modelReleaseId,
            @Param("providerType") String providerType,
            @Param("runtimeMode") String runtimeMode,
            @Param("manifestJson") String manifestJson,
            @Param("ready") boolean ready,
            @Param("recordedAt") Instant recordedAt
    );
}
