package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class BatchDtos {

    private BatchDtos() {
    }

    record BatchJobResponse(
            UUID id,
            String status,
            int totalItems,
            int completedItems,
            int failedItems,
            boolean cancelRequested,
            String modelReleaseId,
            int progressPercent,
            List<BatchItemResponse> items,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record BatchItemResponse(
            UUID id,
            String imageName,
            String question,
            String status,
            String answer,
            String predictionOrigin,
            Double confidence,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            int attemptCount
    ) {
    }
}
