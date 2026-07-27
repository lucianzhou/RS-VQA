package com.rsvqa.gateway;

import static com.rsvqa.gateway.AnalyticsDtos.*;
import static com.rsvqa.gateway.ReportDtos.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ReportEntity;
import com.rsvqa.gateway.domain.ReportVersionEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.ReportRepository;
import com.rsvqa.gateway.repository.ReportVersionRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
public class ReportService {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final BatchJobRepository batchJobs;
    private final ReportRepository reports;
    private final ReportVersionRepository versions;
    private final AnalyticsService analytics;
    private final ObjectMapper objectMapper;

    public ReportService(
            UserRepository users,
            ProjectRepository projects,
            BatchJobRepository batchJobs,
            ReportRepository reports,
            ReportVersionRepository versions,
            AnalyticsService analytics,
            ObjectMapper objectMapper
    ) {
        this.users = users;
        this.projects = projects;
        this.batchJobs = batchJobs;
        this.reports = reports;
        this.versions = versions;
        this.analytics = analytics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReportResponse create(CreateReportRequest request) {
        if ((request.projectId() == null) == (request.batchJobId() == null)) {
            throw new RequestValidationException("报告必须且只能选择一个项目或批量任务。");
        }
        UserEntity user = currentUser();
        ProjectEntity project = request.projectId() == null ? null
                : projects.findByIdAndUserId(request.projectId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
        BatchJobEntity batch = request.batchJobId() == null ? null
                : batchJobs.findByIdAndUserId(request.batchJobId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("批量任务不存在。"));
        AnalysisStatistics facts = project == null ? analytics.batch(batch.getId()) : analytics.project(project.getId());
        String defaultTitle = project == null ? facts.scopeName() + "分析报告" : project.getName() + "项目分析报告";
        String title = request.title() == null || request.title().isBlank() ? defaultTitle : request.title().trim();
        ReportEntity report = reports.save(new ReportEntity(
                user,
                project,
                batch,
                title,
                project == null ? "BATCH_ANALYSIS" : "PROJECT_ANALYSIS",
                TraceId.current()
        ));
        versions.save(version(report, report.getCurrentVersion(), facts));
        return response(report);
    }

    @Transactional(readOnly = true)
    public List<ReportSummary> list() {
        UUID userId = currentUser().getId();
        return reports.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public ReportResponse get(UUID reportId) {
        return response(owned(reportId));
    }

    @Transactional
    public ReportResponse regenerate(UUID reportId) {
        ReportEntity report = owned(reportId);
        AnalysisStatistics facts = report.getProject() == null
                ? analytics.batch(report.getBatchJob().getId())
                : analytics.project(report.getProject().getId());
        report.advanceVersion();
        versions.save(version(report, report.getCurrentVersion(), facts));
        return response(report);
    }

    @Transactional
    public ReportResponse confirm(UUID reportId) {
        ReportEntity report = owned(reportId);
        report.confirm();
        return response(report);
    }

    @Transactional(readOnly = true)
    public ExportContent export(UUID reportId, String format) {
        ReportEntity report = owned(reportId);
        ReportVersionEntity version = currentVersion(report);
        String normalized = format == null ? "md" : format.toLowerCase();
        if ("json".equals(normalized)) {
            String body = json(Map.of(
                    "report", summary(report),
                    "version", toVersion(version),
                    "boundary", "deterministic facts and provenance; generated explanations are separated"
            ));
            return new ExportContent(
                    body.getBytes(StandardCharsets.UTF_8),
                    safeName(report.getTitle()) + ".json",
                    "application/json"
            );
        }
        if (!"md".equals(normalized) && !"markdown".equals(normalized)) {
            throw new RequestValidationException("报告仅支持 md 或 json 导出。");
        }
        return new ExportContent(
                version.getMarkdownContent().getBytes(StandardCharsets.UTF_8),
                safeName(report.getTitle()) + ".md",
                "text/markdown"
        );
    }

    private ReportVersionEntity version(ReportEntity report, int number, AnalysisStatistics facts) {
        String releaseId = facts.modelReleaseIds().size() == 1 ? facts.modelReleaseIds().getFirst()
                : facts.modelReleaseIds().isEmpty() ? null : "multiple-releases";
        return new ReportVersionEntity(
                report,
                number,
                json(facts),
                markdown(report, facts, number),
                null,
                "[]",
                releaseId,
                "deterministic_backend_statistics",
                "JAVA_ANALYTICS_SERVICE"
        );
    }

    private String markdown(ReportEntity report, AnalysisStatistics facts, int version) {
        StringBuilder body = new StringBuilder()
                .append("# ").append(report.getTitle()).append("\n\n")
                .append("> 状态：").append(report.getStatus()).append(" · 版本：v").append(version)
                .append(" · 请求编号：`").append(report.getRequestId()).append("`\n\n")
                .append("## 范围与来源\n\n")
                .append("- 范围：").append(facts.scopeType()).append(" / ").append(facts.scopeName()).append("\n")
                .append("- 图像：").append(facts.imageCount()).append("\n")
                .append("- 问题/调用：").append(facts.questionCount()).append("\n")
                .append("- 模型发布：").append(facts.modelReleaseIds().isEmpty() ? "无已记录发布" : String.join(", ", facts.modelReleaseIds())).append("\n")
                .append("- 生成时间：").append(Instant.now()).append("\n\n")
                .append("## 确定性统计\n\n")
                .append("| 指标 | 数值 |\n| --- | ---: |\n")
                .append("| 已回答 | ").append(facts.answeredCount()).append(" |\n")
                .append("| 超出范围/拒答 | ").append(facts.unsupportedCount()).append(" |\n")
                .append("| 失败 | ").append(facts.failedCount()).append(" |\n")
                .append("| 明确复核项 | ").append(facts.reviewRecommendedCount()).append(" |\n")
                .append("| 平均置信度 | ").append(value(facts.averageConfidence())).append(" |\n")
                .append("| 平均 margin | ").append(value(facts.averageMargin())).append(" |\n\n");
        appendDistribution(body, "问题类型分布", facts.questionTypeDistribution());
        appendDistribution(body, "答案分布（前 20）", facts.answerDistribution());
        appendDistribution(body, "输出来源分布", facts.originDistribution());
        appendDistribution(body, "置信度区间", facts.confidenceDistribution());
        body.append("## 需要人工复核\n\n");
        if (facts.reviewCases().isEmpty()) {
            body.append("当前持久化记录中没有超范围、调用失败或答案形式异常案例。\n\n");
        } else {
            body.append("| 图像/会话 | 问题 | 原始答案 | 状态 | 复核原因 | 置信度 | 请求编号 |\n")
                    .append("| --- | --- | --- | --- | --- | ---: | --- |\n");
            for (AnalysisCase item : facts.reviewCases()) {
                body.append("| ").append(cell(item.scopeLabel())).append(" | ").append(cell(item.question()))
                        .append(" | ").append(cell(item.answer())).append(" | ").append(cell(item.status()))
                        .append(" | ").append(cell(item.reviewReason()))
                        .append(" | ").append(value(item.confidence())).append(" | `")
                        .append(cell(item.requestId())).append("` |\n");
            }
            body.append('\n');
        }
        body.append("## 能力边界\n\n")
                .append("- 研究模型是 RSVQA-HR grouped-answer 的 55 类闭集分类器，不是开放式通用遥感助手。\n")
                .append("- 系统不根据固定置信度阈值自动拒答；confidence、margin 和分箱只描述模型输出分布。\n")
                .append("- Count 对非零和密集目标存在系统性低估风险，数量结论应结合业务要求人工复核。\n")
                .append("- 本报告中的数量、分布与均值由后端确定性计算；尚未配置的 Agent 摘要不会被伪造。\n")
                .append("- Mock、研究模型、外部 VLM 与 Agent 输出必须依据 provenance 分开解释。\n\n")
                .append("---\n").append(facts.calculationBoundary()).append('\n');
        return body.toString();
    }

    private static void appendDistribution(StringBuilder body, String title, Map<String, Long> distribution) {
        body.append("### ").append(title).append("\n\n");
        if (distribution.isEmpty()) {
            body.append("暂无可统计记录。\n\n");
            return;
        }
        body.append("| 类别 | 数量 |\n| --- | ---: |\n");
        distribution.forEach((key, count) -> body.append("| ").append(cell(key)).append(" | ").append(count).append(" |\n"));
        body.append('\n');
    }

    private ReportResponse response(ReportEntity report) {
        List<ReportVersionResponse> all = versions.findByReportIdOrderByVersionNumberDesc(report.getId())
                .stream().map(this::toVersion).toList();
        ReportVersionResponse current = all.stream()
                .filter(version -> version.versionNumber() == report.getCurrentVersion())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("报告当前版本不存在。"));
        return new ReportResponse(summary(report), current, all);
    }

    private ReportSummary summary(ReportEntity report) {
        return new ReportSummary(
                report.getId(),
                report.getTitle(),
                report.getStatus(),
                report.getReportType(),
                report.getProject() == null ? null : report.getProject().getId(),
                report.getBatchJob() == null ? null : report.getBatchJob().getId(),
                report.getCurrentVersion(),
                report.getRequestId(),
                report.getConfirmedAt(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }

    private ReportVersionResponse toVersion(ReportVersionEntity version) {
        return new ReportVersionResponse(
                version.getId(),
                version.getVersionNumber(),
                version.getFactsJson(),
                version.getMarkdownContent(),
                version.getAgentSummary(),
                version.getCitationsJson(),
                version.getModelReleaseId(),
                version.getPredictionOrigin(),
                version.getGeneratedBy(),
                version.getCreatedAt()
        );
    }

    private ReportVersionEntity currentVersion(ReportEntity report) {
        return versions.findByReportIdAndVersionNumber(report.getId(), report.getCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("报告当前版本不存在。"));
    }

    private ReportEntity owned(UUID id) {
        return reports.findByIdAndUserId(id, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("报告不存在。"));
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("报告事实无法序列化。", error);
        }
    }

    private static String value(Double value) {
        return value == null ? "N/A" : String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").trim();
        return safe.isBlank() ? "rs-vqa-report" : safe.substring(0, Math.min(80, safe.length()));
    }

    public record ExportContent(byte[] bytes, String filename, String mediaType) {
    }
}
