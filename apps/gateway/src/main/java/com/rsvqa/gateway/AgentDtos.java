package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AgentDtos {

    private AgentDtos() {
    }

    record AgentRequest(
            UUID sessionId,
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

    record CreateAgentSessionRequest(
            UUID projectId,
            UUID conversationId,
            UUID batchJobId,
            @Size(max = 200, message = "Agent 会话标题不能超过 200 个字符。")
            String title
    ) {
    }

    record AgentSessionSummary(
            UUID id,
            String title,
            String contextType,
            UUID contextId,
            String contextLabel,
            int runCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record AgentSessionDetail(
            UUID id,
            String title,
            String contextType,
            UUID contextId,
            String contextLabel,
            List<AgentHistoryRun> runs,
            List<String> suggestedPrompts,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record AgentHistoryRun(
            UUID runId,
            String status,
            String input,
            String answer,
            String traceId,
            Long latencyMs,
            String providerId,
            String providerModel,
            Integer totalTokens,
            List<ToolCallResponse> toolCalls,
            Instant createdAt
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
