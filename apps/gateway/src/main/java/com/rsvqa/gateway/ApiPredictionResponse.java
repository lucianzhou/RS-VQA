package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiPredictionResponse(
        String requestId,
        String status,
        boolean supported,
        String answer,
        Double confidence,
        Double margin,
        List<ModelPredictionResponse.TopKPrediction> topK,
        String canonicalQuestion,
        String questionType,
        String predictedQuestionType,
        Map<String, Double> questionTypeProbabilities,
        String predictionOrigin,
        String modelReleaseId,
        String checkpointSha256,
        String answerVocabularySha256,
        String runtimeArtifactSha256,
        String taskScope,
        List<String> limitations,
        String capabilityNotice,
        Long latencyMs,
        String runtimeMode
) {
    static ApiPredictionResponse from(ModelPredictionResponse response) {
        return new ApiPredictionResponse(
                response.requestId(),
                response.status(),
                response.supported(),
                response.prediction() == null ? response.answer() : response.prediction(),
                response.confidence(),
                response.margin(),
                response.topK(),
                response.canonicalQuestion(),
                response.questionType(),
                response.predictedQuestionType(),
                response.questionTypeProbabilities(),
                response.predictionOrigin(),
                response.modelReleaseId(),
                response.checkpointSha256(),
                response.answerVocabularySha256(),
                response.runtimeArtifactSha256(),
                response.taskScope(),
                response.limitations(),
                response.capabilityNotice(),
                response.latencyMs(),
                response.runtimeMode()
        );
    }

    ApiPredictionResponse(
            String requestId,
            String status,
            boolean supported,
            String answer,
            String canonicalQuestion,
            String questionType,
            String predictionOrigin,
            String modelReleaseId,
            String capabilityNotice
    ) {
        this(
                requestId,
                status,
                supported,
                answer,
                null,
                null,
                List.of(),
                canonicalQuestion,
                questionType,
                questionType,
                Map.of(),
                predictionOrigin,
                modelReleaseId,
                null,
                null,
                null,
                "rsvqa_hr_grouped_closed_set",
                List.of(),
                capabilityNotice,
                null,
                "mock"
        );
    }
}
