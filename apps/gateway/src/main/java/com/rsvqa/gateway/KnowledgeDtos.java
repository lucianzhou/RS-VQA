package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    record KnowledgeDocumentResponse(
            UUID id,
            String title,
            String sha256,
            String mimeType,
            String indexVersion,
            String status,
            String errorMessage,
            Instant createdAt
    ) {
    }

    record SearchKnowledgeRequest(
            @NotBlank @Size(max = 500) String query,
            @Min(1) @Max(20) Integer topK,
            Double threshold
    ) {
    }

    record KnowledgeSearchResponse(
            @JsonAlias("request_id") String requestId,
            String query,
            List<KnowledgeCitation> citations,
            @JsonAlias("latency_ms") long latencyMs,
            @JsonAlias("embedding_model") String embeddingModel,
            String collection
    ) {
    }

    record KnowledgeCitation(
            @JsonAlias("document_id") String documentId,
            String title,
            @JsonAlias("chunk_index") int chunkIndex,
            String content,
            double score,
            @JsonAlias("index_version") String indexVersion
    ) {
    }

    record IndexRuntimeResponse(
            @JsonAlias("document_id") String documentId,
            @JsonAlias("index_version") String indexVersion,
            @JsonAlias("chunk_count") int chunkCount,
            @JsonAlias("embedding_model") String embeddingModel,
            String collection
    ) {
    }

    record IndexRuntimeRequest(
            @JsonProperty("document_id") String documentId,
            String title,
            String text,
            @JsonProperty("index_version") String indexVersion,
            Map<String, String> metadata
    ) {
    }

    record SearchRuntimeRequest(
            String query,
            @JsonProperty("top_k") int topK,
            double threshold,
            @JsonProperty("index_version") String indexVersion
    ) {
    }
}
