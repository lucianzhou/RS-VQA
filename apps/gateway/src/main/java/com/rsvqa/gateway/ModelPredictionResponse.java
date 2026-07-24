package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelPredictionResponse(
        @JsonProperty("request_id") String requestId,
        String status,
        boolean supported,
        String prediction,
        String answer,
        Double confidence,
        Double margin,
        @JsonProperty("top_k") List<TopKPrediction> topK,
        @JsonProperty("canonical_question") String canonicalQuestion,
        @JsonProperty("question_type") String questionType,
        @JsonProperty("predicted_question_type") String predictedQuestionType,
        @JsonProperty("question_type_probabilities") Map<String, Double> questionTypeProbabilities,
        @JsonProperty("prediction_origin") String predictionOrigin,
        @JsonProperty("model_release_id") String modelReleaseId,
        @JsonProperty("checkpoint_sha256") String checkpointSha256,
        @JsonProperty("answer_vocabulary_sha256") String answerVocabularySha256,
        @JsonProperty("runtime_artifact_sha256") String runtimeArtifactSha256,
        @JsonProperty("task_scope") String taskScope,
        List<String> limitations,
        @JsonProperty("capability_notice") String capabilityNotice,
        @JsonProperty("input_sha256") String inputSha256,
        @JsonProperty("latency_ms") Long latencyMs,
        @JsonProperty("runtime_mode") String runtimeMode
) {
    public record TopKPrediction(String answer, double probability) {
    }
}
