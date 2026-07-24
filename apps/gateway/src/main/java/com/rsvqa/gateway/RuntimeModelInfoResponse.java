package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RuntimeModelInfoResponse(
        String mode,
        boolean ready,
        @JsonProperty("model_release_id") String modelReleaseId,
        @JsonProperty("contract_version") String contractVersion,
        @JsonProperty("task_scope") String taskScope,
        @JsonProperty("type_source_mode") String typeSourceMode,
        @JsonProperty("prediction_origin") String predictionOrigin,
        List<String> limitations,
        Map<String, Object> manifest
) {
}
