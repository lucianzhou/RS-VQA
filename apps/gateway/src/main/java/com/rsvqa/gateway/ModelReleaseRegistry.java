package com.rsvqa.gateway;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.repository.ModelReleaseRepository;

@Service
public class ModelReleaseRegistry {

    private final ModelReleaseRepository releases;
    private final ObjectMapper objectMapper;

    public ModelReleaseRegistry(ModelReleaseRepository releases, ObjectMapper objectMapper) {
        this.releases = releases;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(RuntimeModelInfoResponse model) {
        if (model.modelReleaseId() == null || model.modelReleaseId().isBlank()) return;
        String manifest = json(model.manifest());
        releases.upsert(
                UUID.randomUUID(),
                model.modelReleaseId(),
                "mock".equalsIgnoreCase(model.mode()) ? "MOCK" : "RESEARCH_MODEL",
                model.mode(),
                manifest,
                model.ready(),
                Instant.now()
        );
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("模型 manifest 无法保存。", error);
        }
    }
}
