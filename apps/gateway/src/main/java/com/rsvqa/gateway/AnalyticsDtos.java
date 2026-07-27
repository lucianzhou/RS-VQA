package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    record AnalysisCase(
            UUID scopeItemId,
            String scopeLabel,
            String question,
            String answer,
            String status,
            String predictionOrigin,
            String modelReleaseId,
            String predictedQuestionType,
            Double confidence,
            Double margin,
            String requestId,
            String reviewReason
    ) {
    }

    record AnalysisStatistics(
            String scopeType,
            UUID scopeId,
            String scopeName,
            int conversationCount,
            int imageCount,
            int questionCount,
            int answeredCount,
            int unsupportedCount,
            int failedCount,
            int reviewRecommendedCount,
            boolean automaticRejectionEnabled,
            boolean confidenceDisplayEnabled,
            boolean manualReviewSignalEnabled,
            Double averageConfidence,
            Double averageMargin,
            Map<String, Long> questionTypeDistribution,
            Map<String, Long> answerDistribution,
            Map<String, Long> originDistribution,
            Map<String, Long> confidenceDistribution,
            List<String> modelReleaseIds,
            List<AnalysisCase> representativeCases,
            List<AnalysisCase> reviewCases,
            String reviewPolicy,
            String calculationBoundary
    ) {
    }
}
