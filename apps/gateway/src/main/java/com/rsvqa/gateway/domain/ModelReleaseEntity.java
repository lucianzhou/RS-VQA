package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_release")
public class ModelReleaseEntity extends BaseEntity {

    @Column(name = "model_release_id", nullable = false, unique = true, length = 200)
    private String modelReleaseId;

    @Column(name = "provider_type", nullable = false, length = 40)
    private String providerType;

    @Column(name = "runtime_mode", nullable = false, length = 20)
    private String runtimeMode;

    @Column(name = "manifest_json", columnDefinition = "TEXT")
    private String manifestJson;

    @Column(nullable = false)
    private boolean ready;

    protected ModelReleaseEntity() {
    }

    public ModelReleaseEntity(
            String modelReleaseId,
            String providerType,
            String runtimeMode,
            String manifestJson,
            boolean ready
    ) {
        this.modelReleaseId = modelReleaseId;
        this.providerType = providerType;
        this.runtimeMode = runtimeMode;
        this.manifestJson = manifestJson;
        this.ready = ready;
    }

    public void refresh(String runtimeMode, String manifestJson, boolean ready) {
        this.runtimeMode = runtimeMode;
        this.manifestJson = manifestJson;
        this.ready = ready;
    }
}
