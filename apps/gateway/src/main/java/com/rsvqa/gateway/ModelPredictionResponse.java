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
        @JsonProperty("original_question") String originalQuestion,
        @JsonProperty("canonical_question") String canonicalQuestion,
        @JsonProperty("canonical_question_display") String canonicalQuestionDisplay,
        @JsonProperty("model_input_question") String modelInputQuestion,
        @JsonProperty("question_normalizer_version") String questionNormalizerVersion,
        @JsonProperty("matched_intent") String matchedIntent,
        @JsonProperty("matched_objects") List<String> matchedObjects,
        @JsonProperty("question_scope_verification") String questionScopeVerification,
        @JsonProperty("reason_code") String reasonCode,
        @JsonProperty("needs_clarification") Boolean needsClarification,
        @JsonProperty("clarification_options") List<String> clarificationOptions,
        @JsonProperty("interpretation_note") String interpretationNote,
        @JsonProperty("display_answer") String displayAnswer,
        @JsonProperty("display_locale") String displayLocale,
        // Boxed on purpose: a model-service predating this contract omits the key,
        // and null must select the gateway's legacy fallback rather than false.
        @JsonProperty("answer_shape_mismatch") Boolean answerShapeMismatch,
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
        @JsonProperty("review_status") String reviewStatus,
        @JsonProperty("automatic_rejection_enabled") Boolean automaticRejectionEnabled,
        @JsonProperty("confidence_display_enabled") Boolean confidenceDisplayEnabled,
        @JsonProperty("manual_review_signal_enabled") Boolean manualReviewSignalEnabled,
        @JsonProperty("input_sha256") String inputSha256,
        @JsonProperty("latency_ms") Long latencyMs,
        @JsonProperty("runtime_mode") String runtimeMode
) {
    public record TopKPrediction(String answer, double probability) {
    }
}
