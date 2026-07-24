package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    record CreateProjectRequest(
            @NotBlank(message = "项目名称不能为空。")
            @Size(max = 160, message = "项目名称不能超过 160 个字符。")
            String name
    ) {
    }

    record CreateConversationRequest(
            @Size(max = 200, message = "会话标题不能超过 200 个字符。")
            String title
    ) {
    }

    record UpdateProjectRequest(
            @NotBlank(message = "项目名称不能为空。")
            @Size(max = 160, message = "项目名称不能超过 160 个字符。")
            String name
    ) {
    }

    record UpdateConversationRequest(
            @Size(max = 200, message = "会话标题不能超过 200 个字符。")
            String title,
            UUID projectId
    ) {
    }

    record QuestionRequest(
            @NotBlank(message = "问题不能为空。")
            @Size(max = 300, message = "问题不能超过 300 个字符。")
            String question,
            String modelReleaseId,
            String providerId
    ) {
    }

    record ProjectResponse(
            UUID id,
            String name,
            List<ConversationSummary> conversations,
            Instant updatedAt
    ) {
    }

    record ConversationSummary(
            UUID id,
            String title,
            boolean hasImage,
            Instant updatedAt
    ) {
    }

    record ArchivedProjectResponse(
            UUID id,
            String name,
            Instant updatedAt
    ) {
    }

    record ArchivedConversationResponse(
            UUID id,
            UUID projectId,
            String projectName,
            String title,
            Instant updatedAt
    ) {
    }

    record ArchiveResponse(
            List<ArchivedProjectResponse> projects,
            List<ArchivedConversationResponse> conversations
    ) {
    }

    record ConversationResponse(
            UUID id,
            UUID projectId,
            String title,
            ImageResponse image,
            List<MessageResponse> messages,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ImageResponse(
            UUID id,
            String originalName,
            String sha256,
            String mimeType,
            long sizeBytes,
            int width,
            int height,
            String contentUrl
    ) {
    }

    record MessageResponse(
            UUID id,
            String role,
            String sourceType,
            String content,
            String metadataJson,
            InvocationResponse invocation,
            Instant createdAt
    ) {
    }

    record InvocationResponse(
            UUID id,
            String requestId,
            String status,
            String predictionOrigin,
            String modelReleaseId,
            String providerType,
            String providerModel,
            Double confidence,
            Double margin,
            Long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Double estimatedCostUsd
    ) {
    }

    record QuestionResponse(
            MessageResponse userMessage,
            MessageResponse assistantMessage,
            ApiPredictionResponse result
    ) {
    }
}
