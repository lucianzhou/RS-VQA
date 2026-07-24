package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AgentDtos {

    private AgentDtos() {
    }

    record AgentRequest(
            UUID projectId,
            UUID conversationId,
            UUID batchJobId,
            @NotBlank(message = "Agent 问题不能为空。")
            @Size(max = 500, message = "Agent 问题不能超过 500 个字符。")
            String message,
            String toolName
    ) {
    }

    record AgentResponse(
            UUID runId,
            String status,
            String providerState,
            String answer,
            String traceId,
            long latencyMs,
            List<ToolCallResponse> toolCalls,
            List<Map<String, String>> citations,
            String boundaryNotice
    ) {
    }

    record ToolCallResponse(
            UUID id,
            String name,
            String status,
            String inputSummary,
            String output,
            long latencyMs
    ) {
    }
}
