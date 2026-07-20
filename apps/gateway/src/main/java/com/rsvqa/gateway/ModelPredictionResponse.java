package com.rsvqa.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ModelPredictionResponse(
        @JsonProperty("request_id") String requestId,
        String status,
        boolean supported,
        String answer,
        @JsonProperty("canonical_question") String canonicalQuestion,
        @JsonProperty("question_type") String questionType,
        @JsonProperty("prediction_origin") String predictionOrigin,
        @JsonProperty("model_release_id") String modelReleaseId,
        @JsonProperty("capability_notice") String capabilityNotice,
        @JsonProperty("input_sha256") String inputSha256
) {
}
