package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentActionDtos.*;
import static com.rsvqa.gateway.ReportDtos.CreateReportRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.AgentActionProposalEntity;
import com.rsvqa.gateway.domain.AgentSessionEntity;
import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentActionProposalRepository;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.ReportRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
public class AgentActionService {

    static final Set<String> ALLOWED_ACTIONS = Set.of(
            "create_batch_task",
            "retry_batch_failures",
            "save_report_draft",
            "export_report",
            "archive_project",
            "archive_conversation",
            "archive_batch_task"
    );
    private static final Duration PROPOSAL_TTL = Duration.ofMinutes(15);

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final BatchJobRepository batchesRepository;
    private final ReportRepository reportsRepository;
    private final AgentActionProposalRepository proposals;
    private final AuditEventRepository auditEvents;
    private final AgentSessionService sessions;
    private final WorkspaceService workspace;
    private final BatchService batches;
    private final BatchWorker batchWorker;
    private final ReportService reports;
    private final ObjectMapper objectMapper;

    public AgentActionService(
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            BatchJobRepository batchesRepository,
            ReportRepository reportsRepository,
            AgentActionProposalRepository proposals,
            AuditEventRepository auditEvents,
            AgentSessionService sessions,
            WorkspaceService workspace,
            BatchService batches,
            BatchWorker batchWorker,
            ReportService reports,
            ObjectMapper objectMapper
    ) {
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.batchesRepository = batchesRepository;
        this.reportsRepository = reportsRepository;
        this.proposals = proposals;
        this.auditEvents = auditEvents;
        this.sessions = sessions;
        this.workspace = workspace;
        this.batches = batches;
        this.batchWorker = batchWorker;
        this.reports = reports;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ActionProposalResponse propose(CreateProposalRequest request) {
        UserEntity user = currentUser();
        AgentSessionEntity session = request.sessionId() == null ? null : sessions.owned(request.sessionId());
        String actionName = request.actionName().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(actionName)) {
            throw new RequestValidationException("不允许的 Agent 写操作：" + actionName);
        }
        Map<String, Object> arguments = arguments(request, actionName, user.getId());
        String summary = summary(actionName, arguments);
        AgentActionProposalEntity proposal = proposals.save(new AgentActionProposalEntity(
                user, session, actionName, json(arguments), summary, UUID.randomUUID().toString(),
                Instant.now().plus(PROPOSAL_TTL)
        ));
        auditEvents.save(new AuditEventEntity(
                user, "AGENT_ACTION_PROPOSED", "AGENT_ACTION", proposal.getId(), proposal.getRequestId(),
                "PENDING", summary + "；arguments=" + proposal.getArgumentsJson()
                        + "；provider=deterministic_action_controller；tokens=0；cost_usd=0"
        ));
        return response(proposal);
    }

    @Transactional(readOnly = true)
    public List<ActionProposalResponse> list(UUID sessionId) {
        UserEntity user = currentUser();
        if (sessionId != null) sessions.owned(sessionId);
        List<AgentActionProposalEntity> values = sessionId == null
                ? proposals.findByUserIdOrderByCreatedAtDesc(user.getId())
                : proposals.findByUserIdAndAgentSessionIdOrderByCreatedAtDesc(user.getId(), sessionId);
        return values.stream().map(this::response).toList();
    }

    @Transactional
    public ActionProposalResponse confirm(UUID proposalId) {
        UserEntity user = currentUser();
        AgentActionProposalEntity proposal = locked(proposalId, user.getId());
        if (!"PENDING".equals(proposal.getStatus())) return response(proposal);
        try {
            proposal.confirm();
        } catch (IllegalStateException expired) {
            auditEvents.save(event(user, proposal, "AGENT_ACTION_EXPIRED", "EXPIRED", "提案已过期，未执行。"));
            return response(proposal);
        }
        try {
            Map<String, Object> result = execute(proposal);
            proposal.complete(json(result));
            auditEvents.save(event(user, proposal, "AGENT_ACTION_EXECUTED", "SUCCESS", proposal.getResultJson()));
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? "受控操作执行失败。" : error.getMessage();
            proposal.fail("ACTION_EXECUTION_FAILED", json(Map.of("message", message)));
            auditEvents.save(event(user, proposal, "AGENT_ACTION_FAILED", "FAILURE", message));
        }
        return response(proposal);
    }

    @Transactional
    public ActionProposalResponse reject(UUID proposalId) {
        UserEntity user = currentUser();
        AgentActionProposalEntity proposal = locked(proposalId, user.getId());
        if ("PENDING".equals(proposal.getStatus())) {
            proposal.reject();
            auditEvents.save(event(user, proposal, "AGENT_ACTION_REJECTED", "REJECTED", "用户拒绝执行。"));
        }
        return response(proposal);
    }

    private Map<String, Object> execute(AgentActionProposalEntity proposal) {
        Map<String, Object> args = parse(proposal.getArgumentsJson());
        return switch (proposal.getActionName()) {
            case "create_batch_task" -> {
                BatchDtos.BatchJobResponse job = batches.createFromProject(
                        uuid(args, "projectId"), text(args, "modelReleaseId"), strings(args, "questions")
                );
                afterCommit(() -> batchWorker.process(job.id()));
                yield Map.of("batchJobId", job.id(), "status", job.status(), "totalItems", job.totalItems());
            }
            case "retry_batch_failures" -> {
                UUID jobId = uuid(args, "batchJobId");
                BatchDtos.BatchJobResponse job = batches.retryFailed(jobId);
                afterCommit(() -> batchWorker.process(jobId));
                yield Map.of("batchJobId", job.id(), "status", job.status(), "totalItems", job.totalItems());
            }
            case "save_report_draft" -> {
                ReportDtos.ReportResponse report = reports.create(new CreateReportRequest(
                        nullableUuid(args, "projectId"), nullableUuid(args, "batchJobId"), text(args, "title")
                ));
                yield Map.of("reportId", report.report().id(), "status", report.report().status(),
                        "version", report.report().currentVersion());
            }
            case "export_report" -> {
                UUID reportId = uuid(args, "reportId");
                String format = text(args, "format");
                ReportService.ExportContent content = reports.export(reportId, format);
                yield Map.of("reportId", reportId, "filename", content.filename(), "mediaType", content.mediaType(),
                        "downloadUrl", "/api/v1/reports/" + reportId + "/export?format=" + format);
            }
            case "archive_project" -> {
                UUID projectId = uuid(args, "projectId");
                workspace.archiveProject(projectId);
                yield Map.of("projectId", projectId, "archived", true);
            }
            case "archive_conversation" -> {
                UUID conversationId = uuid(args, "conversationId");
                workspace.archiveConversation(conversationId);
                yield Map.of("conversationId", conversationId, "archived", true);
            }
            case "archive_batch_task" -> {
                UUID batchJobId = uuid(args, "batchJobId");
                batches.archive(batchJobId);
                yield Map.of("batchJobId", batchJobId, "archived", true);
            }
            default -> throw new RequestValidationException("不允许的 Agent 写操作。");
        };
    }

    private Map<String, Object> arguments(CreateProposalRequest request, String action, UUID userId) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (action) {
            case "create_batch_task" -> {
                requireOwnedProject(request.projectId(), userId);
                List<String> questions = request.questions() == null ? List.of() : request.questions().stream()
                        .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
                if (questions.isEmpty()) throw new RequestValidationException("创建批任务至少需要一个问题。");
                args.put("projectId", request.projectId());
                args.put("questions", questions);
                args.put("modelReleaseId", value(request.modelReleaseId()));
            }
            case "retry_batch_failures", "archive_batch_task" -> {
                requireOwnedBatch(request.batchJobId(), userId);
                args.put("batchJobId", request.batchJobId());
            }
            case "save_report_draft" -> {
                if ((request.projectId() == null) == (request.batchJobId() == null)) {
                    throw new RequestValidationException("报告草稿必须且只能选择一个项目或批任务。");
                }
                if (request.projectId() != null) requireOwnedProject(request.projectId(), userId);
                if (request.batchJobId() != null) requireOwnedBatch(request.batchJobId(), userId);
                args.put("projectId", value(request.projectId()));
                args.put("batchJobId", value(request.batchJobId()));
                args.put("title", value(request.title()));
            }
            case "export_report" -> {
                requireOwnedReport(request.reportId(), userId);
                String format = request.format() == null ? "md" : request.format().trim().toLowerCase(Locale.ROOT);
                if (!Set.of("md", "markdown", "json").contains(format)) {
                    throw new RequestValidationException("报告仅支持 md 或 json 导出。");
                }
                args.put("reportId", request.reportId());
                args.put("format", format);
            }
            case "archive_project" -> {
                requireOwnedProject(request.projectId(), userId);
                args.put("projectId", request.projectId());
            }
            case "archive_conversation" -> {
                if (request.conversationId() == null || conversations
                        .findByIdAndProjectUserId(request.conversationId(), userId).isEmpty()) {
                    throw new ResourceNotFoundException("会话不存在。");
                }
                args.put("conversationId", request.conversationId());
            }
            default -> throw new RequestValidationException("不允许的 Agent 写操作。");
        }
        return args;
    }

    private void requireOwnedProject(UUID id, UUID userId) {
        if (id == null || projects.findByIdAndUserId(id, userId).isEmpty()) {
            throw new ResourceNotFoundException("项目不存在。");
        }
    }

    private void requireOwnedBatch(UUID id, UUID userId) {
        if (id == null || batchesRepository.findByIdAndUserId(id, userId).isEmpty()) {
            throw new ResourceNotFoundException("批量任务不存在。");
        }
    }

    private void requireOwnedReport(UUID id, UUID userId) {
        if (id == null || reportsRepository.findByIdAndUserId(id, userId).isEmpty()) {
            throw new ResourceNotFoundException("报告不存在。");
        }
    }

    private AgentActionProposalEntity locked(UUID id, UUID userId) {
        return proposals.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("操作提案不存在。"));
    }

    private AuditEventEntity event(UserEntity user, AgentActionProposalEntity proposal, String type,
            String outcome, String result) {
        return new AuditEventEntity(user, type, "AGENT_ACTION", proposal.getId(), proposal.getRequestId(), outcome,
                proposal.getSummary() + "；arguments=" + proposal.getArgumentsJson() + "；result=" + result
                        + "；provider=" + proposal.getProviderId()
                        + "/" + proposal.getProviderModel() + "；tokens=" + proposal.getTotalTokens()
                        + "；cost_usd=" + proposal.getEstimatedCostUsd());
    }

    private ActionProposalResponse response(AgentActionProposalEntity proposal) {
        return new ActionProposalResponse(
                proposal.getId(), proposal.getAgentSession() == null ? null : proposal.getAgentSession().getId(),
                proposal.getActionName(), proposal.getSummary(), proposal.getStatus(), proposal.getRequestId(),
                proposal.getProviderId(), proposal.getProviderModel(), proposal.getPromptTokens(),
                proposal.getCompletionTokens(), proposal.getTotalTokens(), proposal.getEstimatedCostUsd(),
                proposal.getResultJson(), proposal.getErrorCode(), proposal.getConfirmedAt(), proposal.getExecutedAt(),
                proposal.getExpiresAt(), proposal.getCreatedAt(), proposal.getUpdatedAt()
        );
    }

    private static String summary(String actionName, Map<String, Object> arguments) {
        return switch (actionName) {
            case "create_batch_task" -> "从项目已有影像创建批量 VQA 任务，共 "
                    + ((List<?>) arguments.get("questions")).size() + " 个问题";
            case "retry_batch_failures" -> "重试批量任务中的失败项";
            case "save_report_draft" -> "保存确定性分析报告草稿";
            case "export_report" -> "准备报告导出文件";
            case "archive_project" -> "归档项目";
            case "archive_conversation" -> "归档对话";
            case "archive_batch_task" -> "归档批量任务";
            default -> actionName;
        };
    }

    private static void afterCommit(Runnable task) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { task.run(); }
        });
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("操作提案参数无法读取。", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("操作提案无法序列化。", error);
        }
    }

    private static UUID uuid(Map<String, Object> args, String key) {
        UUID value = nullableUuid(args, key);
        if (value == null) throw new RequestValidationException("操作参数缺少 " + key + "。");
        return value;
    }

    private static UUID nullableUuid(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : UUID.fromString(String.valueOf(value));
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static Object value(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : value;
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }
}
