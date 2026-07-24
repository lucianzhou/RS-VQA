package com.rsvqa.gateway;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Size;

final class ReportDtos {

    private ReportDtos() {
    }

    record CreateReportRequest(
            UUID projectId,
            UUID batchJobId,
            @Size(max = 200, message = "报告标题不能超过 200 个字符。")
            String title
    ) {
    }

    record ReportSummary(
            UUID id,
            String title,
            String status,
            String reportType,
            UUID projectId,
            UUID batchJobId,
            int currentVersion,
            String requestId,
            Instant confirmedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    record ReportVersionResponse(
            UUID id,
            int versionNumber,
            String factsJson,
            String markdownContent,
            String agentSummary,
            String citationsJson,
            String modelReleaseId,
            String predictionOrigin,
            String generatedBy,
            Instant createdAt
    ) {
    }

    record ReportResponse(
            ReportSummary report,
            ReportVersionResponse current,
            List<ReportVersionResponse> versions
    ) {
    }
}
