package com.rsvqa.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AgentActionDtos {

    private AgentActionDtos() {
    }

    record CreateProposalRequest(
            UUID sessionId,
            @NotBlank @Size(max = 80) String actionName,
            UUID projectId,
            UUID conversationId,
            UUID batchJobId,
            UUID reportId,
            @Size(max = 32) List<@NotBlank @Size(max = 300) String> questions,
            @Size(max = 200) String title,
            @Size(max = 20) String format,
            @Size(max = 200) String modelReleaseId
    ) {
    }

    record ActionProposalResponse(
            UUID id,
            UUID sessionId,
            String actionName,
            String summary,
            String status,
            String requestId,
            String providerId,
            String providerModel,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            BigDecimal estimatedCostUsd,
            String resultJson,
            String errorCode,
            Instant confirmedAt,
            Instant executedAt,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
