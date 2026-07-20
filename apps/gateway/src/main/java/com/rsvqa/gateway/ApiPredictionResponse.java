package com.rsvqa.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiPredictionResponse(
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
    static ApiPredictionResponse from(ModelPredictionResponse response) {
        return new ApiPredictionResponse(
                response.requestId(),
                response.status(),
                response.supported(),
                response.answer(),
                response.canonicalQuestion(),
                response.questionType(),
                response.predictionOrigin(),
                response.modelReleaseId(),
                response.capabilityNotice()
        );
    }
}
