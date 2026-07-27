package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            boolean archived,
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
            Double margin,
            String predictedQuestionType,
            String requestId,
            String modelReleaseId,
            String checkpointSha256,
            String answerVocabularySha256,
            String runtimeArtifactSha256,
            String imageSha256,
            List<ModelPredictionResponse.TopKPrediction> topK,
            Map<String, Double> questionTypeProbabilities,
            String canonicalQuestion,
            String modelInputQuestion,
            String questionNormalizerVersion,
            String matchedIntent,
            String questionScopeVerification,
            boolean answerShapeMismatch,
            String taskScope,
            List<String> limitations,
            String capabilityNotice,
            String reviewStatus,
            boolean automaticRejectionEnabled,
            boolean confidenceDisplayEnabled,
            boolean manualReviewSignalEnabled,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            int attemptCount
    ) {
    }
}
