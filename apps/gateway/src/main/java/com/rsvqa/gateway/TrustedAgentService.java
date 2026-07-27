package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.AgentRunEntity;
import com.rsvqa.gateway.domain.AgentSessionEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ToolInvocationEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentRunRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ToolInvocationRepository;
import com.rsvqa.gateway.repository.UserRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class TrustedAgentService {

    static final int MAX_SESSION_RUNS = 50;

    static final String PROVIDER_STATE_LLM = "LLM_PLANNING";
    static final String PROVIDER_STATE_RULES = "RULE_BASED_TOOLS";
    /**
     * Shown to ordinary users instead of an opaque status token. They need to
     * know planning is off, not to decode an enum name.
     */
    static final String RULE_BASED_LABEL = "RS-Bot 当前处于规则工具模式，未启用智能规划";

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final AgentRunRepository runs;
    private final ToolInvocationRepository toolInvocations;
    private final AgentSessionService agentSessions;
    private final TrustedAgentTools tools;
    private final AgentToolRegistry toolRegistry;
    private final RsBotPlanner planner;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Timer runTimer;
    private final Counter runErrors;

    public TrustedAgentService(
            UserRepository users,
            ConversationRepository conversations,
            AgentRunRepository runs,
            ToolInvocationRepository toolInvocations,
            AgentSessionService agentSessions,
            TrustedAgentTools tools,
            AgentToolRegistry toolRegistry,
            RsBotPlanner planner,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MeterRegistry registry
    ) {
        this.users = users;
        this.conversations = conversations;
        this.runs = runs;
        this.toolInvocations = toolInvocations;
        this.agentSessions = agentSessions;
        this.tools = tools;
        this.toolRegistry = toolRegistry;
        this.planner = planner;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.runTimer = Timer.builder("rsvqa.agent.run")
                .description("Trusted Agent run latency")
                .publishPercentileHistogram()
                .register(registry);
        this.runErrors = Counter.builder("rsvqa.agent.errors")
                .description("Trusted Agent failures")
                .register(registry);
    }

    public AgentResponse run(AgentRequest request) {
        return run(request, () -> false);
    }

    /**
     * @param cancelled polled between planning steps so a disconnected SSE client
     *                  stops the loop instead of paying for the remaining steps.
     */
    public AgentResponse run(AgentRequest request, BooleanSupplier cancelled) {
        Timer.Sample metricSample = Timer.start();
        long started = System.nanoTime();
        StartedRun startedRun = transaction(() -> startRun(request));

        if (planner.available()
                && (startedRun.request().toolName() == null || startedRun.request().toolName().isBlank())) {
            return runPlanned(startedRun.request(), startedRun.runId(), started, metricSample, cancelled);
        }
        return runRuleBased(startedRun.request(), startedRun.runId(), started, metricSample);
    }

    private StartedRun startRun(AgentRequest request) {
        UserEntity user = currentUser();
        AgentSessionEntity session = request.sessionId() == null ? null : agentSessions.owned(request.sessionId());
        if (session != null && runs.countByAgentSessionId(session.getId()) >= MAX_SESSION_RUNS) {
            throw new RequestValidationException("单个 Agent 会话最多执行 50 轮，请新建分析会话继续。");
        }
        AgentRequest resolvedRequest = resolveContext(request, session);
        ConversationEntity conversation = resolvedRequest.conversationId() == null ? null
                : conversations.findByIdAndProjectUserId(resolvedRequest.conversationId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("会话不存在。"));
        ProjectEntity project = session == null ? null : session.getProject();
        BatchJobEntity batchJob = session == null ? null : session.getBatchJob();
        AgentRunEntity run = runs.save(new AgentRunEntity(
                user, session, project, conversation, batchJob, resolvedRequest.message().trim(), TraceId.current()
        ));
        titleFromFirstQuestion(session, resolvedRequest.message());
        return new StartedRun(run.getId(), resolvedRequest);
    }

    /**
     * LLM planning: the model chooses tools, {@link RsBotPlanner} decides whether
     * it may, and the tool outputs remain the only source of facts.
     */
    private AgentResponse runPlanned(
            AgentRequest request,
            UUID runId,
            long started,
            Timer.Sample metricSample,
            BooleanSupplier cancelled
    ) {
        try {
            RsBotPlanner.PlanResult plan = planner.plan(
                    request,
                    toolRegistry.callbacks(),
                    cancelled,
                    tool -> transaction(() -> {
                        recordPlannedTool(runId, tool);
                        return null;
                    })
            );
            return transaction(() -> completePlannedRun(runId, plan, elapsedMillis(started)));
        } catch (RuntimeException error) {
            runErrors.increment();
            long latency = elapsedMillis(started);
            String safeMessage = error.getMessage() == null ? "智能规划失败。" : error.getMessage();
            transaction(() -> {
                failRun(runId, "AGENT_PLANNING_FAILED", safeMessage, latency);
                return null;
            });
            throw error;
        } finally {
            metricSample.stop(runTimer);
        }
    }

    /** Deterministic single-tool orchestration used when no planning model is configured. */
    private AgentResponse runRuleBased(
            AgentRequest resolvedRequest,
            UUID runId,
            long started,
            Timer.Sample metricSample
    ) {
        String toolName = chooseTool(resolvedRequest);
        String arguments = argumentsSummary(resolvedRequest, toolName);
        UUID invocationId = transaction(() -> startTool(runId, toolName, arguments));
        long toolStarted = System.nanoTime();
        try {
            Object output = execute(toolName, resolvedRequest);
            long toolLatency = elapsedMillis(toolStarted);
            String outputJson = json(output);
            String answer = explain(toolName, output);
            long latency = elapsedMillis(started);
            return transaction(() -> completeRuleBasedRun(
                    runId, invocationId, toolName, arguments, outputJson, toolLatency, answer, latency));
        } catch (RuntimeException error) {
            runErrors.increment();
            long toolLatency = elapsedMillis(toolStarted);
            String safeMessage = error.getMessage() == null ? "工具调用失败。" : error.getMessage();
            long latency = elapsedMillis(started);
            transaction(() -> {
                failRuleBasedRun(runId, invocationId, safeMessage, toolLatency, latency);
                return null;
            });
            throw error;
        } finally {
            metricSample.stop(runTimer);
        }
    }

    private void recordPlannedTool(UUID runId, RsBotPlanner.ExecutedTool tool) {
        ToolInvocationEntity invocation = toolInvocations.save(
                new ToolInvocationEntity(requireRun(runId), tool.name(), tool.arguments()));
        if ("COMPLETED".equals(tool.status())) {
            invocation.complete(tool.output(), tool.latencyMs());
        } else if ("REJECTED".equals(tool.status())) {
            invocation.reject("TOOL_NOT_ALLOWED", tool.errorMessage(), tool.latencyMs());
        } else {
            invocation.fail("TOOL_CALL_FAILED", tool.errorMessage(), tool.latencyMs());
        }
    }

    private AgentResponse completePlannedRun(
            UUID runId,
            RsBotPlanner.PlanResult plan,
            long latency
    ) {
        AgentRunEntity run = requireRun(runId);
        run.complete(plan.answer(), latency);
        run.recordProvenance(
                GeminiRelayVisionProvider.PROVIDER_ID, plan.providerModel(), PROVIDER_STATE_LLM,
                RsBotProperties.PROMPT_VERSION, plan.stopReason(), plan.steps(),
                plan.promptTokens(), plan.completionTokens(), plan.totalTokens());
        List<ToolCallResponse> calls = toolInvocations
                .findByAgentRunIdOrderByCreatedAtAsc(runId)
                .stream()
                .map(this::toolResponse)
                .toList();
        return new AgentResponse(
                run.getId(),
                run.getStatus(),
                PROVIDER_STATE_LLM,
                "智能规划已启用",
                plan.answer(),
                run.getTraceId(),
                latency,
                calls,
                List.of(),
                "RS-Bot 的结论基于上述工具返回的事实；研究模型的原始答案不会被改写，"
                        + "写操作需要你在界面上确认。",
                plan.providerModel(),
                RsBotProperties.PROMPT_VERSION,
                plan.stopReason(),
                plan.steps(),
                plan.totalTokens()
        );
    }

    private UUID startTool(UUID runId, String toolName, String arguments) {
        return toolInvocations.save(
                new ToolInvocationEntity(requireRun(runId), toolName, arguments)
        ).getId();
    }

    private AgentResponse completeRuleBasedRun(
            UUID runId,
            UUID invocationId,
            String toolName,
            String arguments,
            String outputJson,
            long toolLatency,
            String answer,
            long latency
    ) {
        AgentRunEntity run = requireRun(runId);
        ToolInvocationEntity invocation = requireTool(invocationId);
        invocation.complete(outputJson, toolLatency);
        run.complete(answer, latency);
        run.recordProvenance(
                null, null, PROVIDER_STATE_RULES, RsBotProperties.PROMPT_VERSION,
                "rule_based_single_tool", 1, null, null, null);
        return new AgentResponse(
                run.getId(),
                run.getStatus(),
                PROVIDER_STATE_RULES,
                RULE_BASED_LABEL,
                answer,
                run.getTraceId(),
                latency,
                List.of(new ToolCallResponse(
                        invocation.getId(), toolName, "COMPLETED", arguments, outputJson, toolLatency
                )),
                List.of(),
                RULE_BASED_LABEL + "：本轮只按关键词选择了一个只读工具并解释其结果，"
                        + "不会生成或改写研究模型答案。",
                null,
                RsBotProperties.PROMPT_VERSION,
                "rule_based_single_tool",
                1,
                null
        );
    }

    private void failRuleBasedRun(
            UUID runId,
            UUID invocationId,
            String message,
            long toolLatency,
            long runLatency
    ) {
        requireTool(invocationId).fail("TOOL_CALL_FAILED", message, toolLatency);
        failRun(runId, "TOOL_CALL_FAILED", message, runLatency);
    }

    private void failRun(UUID runId, String code, String message, long latency) {
        requireRun(runId).fail(code, message, latency);
    }

    private AgentRunEntity requireRun(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 运行不存在。"));
    }

    private ToolInvocationEntity requireTool(UUID invocationId) {
        return toolInvocations.findById(invocationId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent 工具调用不存在。"));
    }

    private ToolCallResponse toolResponse(ToolInvocationEntity invocation) {
        return new ToolCallResponse(
                invocation.getId(),
                invocation.getToolName(),
                invocation.getStatus(),
                invocation.getArgumentsSummary(),
                invocation.getOutputSummary(),
                invocation.getLatencyMs() == null ? 0 : invocation.getLatencyMs()
        );
    }

    private <T> T transaction(Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }

    private record StartedRun(UUID runId, AgentRequest request) {
    }

    private Object execute(String toolName, AgentRequest request) {
        return switch (toolName) {
            case "current_model_release" -> tools.currentModelRelease();
            case "supported_question_types", "model_capabilities" -> tools.modelCapabilities();
            case "system_health" -> tools.systemHealth();
            case "single_image_vqa" -> {
                if (request.conversationId() == null) {
                    throw new RequestValidationException("调用单图 VQA 需要 conversationId。");
                }
                yield toolRegistry.singleImageVqa(
                        request.conversationId().toString(),
                        request.message().trim()
                );
            }
            case "conversation_history" -> {
                if (request.conversationId() == null) {
                    throw new RequestValidationException("查询会话历史需要 conversationId。");
                }
                yield tools.conversationHistory(request.conversationId().toString());
            }
            case "conversation_vqa_results" -> {
                if (request.conversationId() == null) {
                    throw new RequestValidationException("查询会话 VQA 结果需要 conversationId。");
                }
                yield tools.conversationVqaResults(request.conversationId().toString());
            }
            case "batch_job_status" -> {
                if (request.batchJobId() == null) {
                    throw new RequestValidationException("查询批量任务需要 batchJobId。");
                }
                yield tools.batchJobStatus(request.batchJobId().toString());
            }
            case "project_vqa_statistics" -> {
                if (request.projectId() == null) {
                    throw new RequestValidationException("项目统计需要 projectId。");
                }
                yield tools.projectVqaStatistics(request.projectId().toString());
            }
            case "project_summary" -> {
                if (request.projectId() == null) {
                    throw new RequestValidationException("项目摘要需要 projectId。");
                }
                yield tools.projectSummary(request.projectId().toString());
            }
            case "project_conversations" -> {
                if (request.projectId() == null) {
                    throw new RequestValidationException("项目会话列表需要 projectId。");
                }
                yield tools.projectConversations(request.projectId().toString());
            }
            case "batch_result_statistics" -> {
                if (request.batchJobId() == null) {
                    throw new RequestValidationException("批量统计需要 batchJobId。");
                }
                yield tools.batchResultStatistics(request.batchJobId().toString());
            }
            case "report_draft_data" -> {
                if (request.projectId() != null) {
                    yield tools.reportDraftData("project", request.projectId().toString());
                }
                if (request.batchJobId() != null) {
                    yield tools.reportDraftData("batch", request.batchJobId().toString());
                }
                throw new RequestValidationException("报告事实包需要 projectId 或 batchJobId。");
            }
            case "confidence_distribution" -> tools.confidenceDistribution(scopeType(request), scopeId(request));
            case "unsupported_question_summary" -> tools.unsupportedQuestionSummary(scopeType(request), scopeId(request));
            case "failed_invocation_summary" -> tools.failedInvocationSummary(scopeType(request), scopeId(request));
            case "search_knowledge", "knowledge_search" -> tools.knowledgeSearch(request.message().trim());
            case "audit_lookup" -> tools.auditLookup(request.message().trim());
            case "create_batch_plan" -> tools.createBatchPlan(scopeType(request), scopeId(request));
            default -> throw new RequestValidationException("不在白名单中的 Agent 工具：" + toolName);
        };
    }

    private String chooseTool(AgentRequest request) {
        if (request.toolName() != null && !request.toolName().isBlank()) {
            return request.toolName().trim();
        }
        String message = request.message().toLowerCase(Locale.ROOT);
        if ((message.contains("报告") || message.contains("草稿")) && (request.projectId() != null || request.batchJobId() != null)) {
            return "report_draft_data";
        }
        if (message.contains("审计") || message.contains("trace")) {
            return "audit_lookup";
        }
        if ((message.contains("计划") || message.contains("规划"))
                && (request.projectId() != null || request.batchJobId() != null)) {
            return "create_batch_plan";
        }
        if ((message.contains("失败") || message.contains("错误"))
                && (request.projectId() != null || request.batchJobId() != null)) {
            return "failed_invocation_summary";
        }
        if ((message.contains("不支持") || message.contains("超范围") || message.contains("拒答"))
                && (request.projectId() != null || request.batchJobId() != null)) {
            return "unsupported_question_summary";
        }
        if (message.contains("置信度") && (request.projectId() != null || request.batchJobId() != null)) {
            return "confidence_distribution";
        }
        if (message.contains("批量") || message.contains("任务")) {
            if (message.contains("统计") || message.contains("分布") || message.contains("汇总")) {
                return "batch_result_statistics";
            }
            return "batch_job_status";
        }
        if ((message.contains("项目") || message.contains("统计") || message.contains("分布") || message.contains("汇总"))
                && request.projectId() != null) {
            if (message.contains("会话") || message.contains("对话")) return "project_conversations";
            if (message.contains("摘要") || message.contains("概览")) return "project_summary";
            return "project_vqa_statistics";
        }
        if ((message.contains("结果") || message.contains("预测")) && request.conversationId() != null) {
            return "conversation_vqa_results";
        }
        if (message.contains("历史") || message.contains("会话") || message.contains("对话")) {
            return "conversation_history";
        }
        if (message.contains("健康") || message.contains("状态")) {
            return "system_health";
        }
        if (message.contains("支持") || message.contains("范围") || message.contains("类型")) {
            return "model_capabilities";
        }
        if (message.contains("检索") || message.contains("知识") || message.contains("论文") || message.contains("指标")) {
            return "knowledge_search";
        }
        if (request.conversationId() != null
                && (message.contains("图中") || message.contains("图里")
                || message.contains("is there") || message.contains("are there")
                || message.contains("how many") || message.contains("面积"))) {
            return "single_image_vqa";
        }
        return "current_model_release";
    }

    /**
     * Replaces the placeholder title with one derived from the opening question.
     *
     * <p>Only on the first run, and only while the title is still a placeholder,
     * so a title the user set by hand is never overwritten.
     */
    private void titleFromFirstQuestion(AgentSessionEntity session, String question) {
        if (session == null || !AgentSessionTitle.isPlaceholder(session.getTitle())) {
            return;
        }
        if (runs.countByAgentSessionId(session.getId()) > 1) {
            return;
        }
        session.rename(AgentSessionTitle.derive(contextLabel(session), question));
    }

    private static String contextLabel(AgentSessionEntity session) {
        if (session.getProject() != null) return session.getProject().getName();
        if (session.getConversation() != null) return session.getConversation().getTitle();
        if (session.getBatchJob() != null) {
            return "批量任务 " + session.getBatchJob().getId().toString().substring(0, 8);
        }
        return "";
    }

    private static AgentRequest resolveContext(AgentRequest request, AgentSessionEntity session) {
        if (session == null) return request;
        return new AgentRequest(
                session.getId(),
                session.getProject() == null ? null : session.getProject().getId(),
                session.getConversation() == null ? null : session.getConversation().getId(),
                session.getBatchJob() == null ? null : session.getBatchJob().getId(),
                request.message(),
                request.toolName()
        );
    }

    private String explain(String toolName, Object output) {
        return switch (toolName) {
            case "current_model_release" -> {
                RuntimeModelInfoResponse model = (RuntimeModelInfoResponse) output;
                yield "当前模型运行模式为 " + model.mode() + "，发布标识为 "
                        + (model.modelReleaseId() == null ? "未加载" : model.modelReleaseId())
                        + "。该信息来自模型服务的只读版本接口。";
            }
            case "supported_question_types", "model_capabilities" -> "研究模型面向是否存在、数量、面积和比较等已验证闭集问题；不应当作开放式遥感视觉助手使用。";
            case "system_health" -> "业务服务可用，工具已读取当前模型运行时状态；外部通用模型仍未配置。";
            case "single_image_vqa" -> "已调用当前会话的受控 VQA 主路径并保存模型原始结果。Agent 只说明调用完成，不会改写答案或提升置信度。";
            case "conversation_history" -> "已读取当前登录用户的会话历史。下面的工具输出是持久化记录，不会被当前模型版本覆盖。";
            case "conversation_vqa_results" -> "已读取会话中带模型调用 provenance 的 VQA 结果；普通聊天内容不会混入模型结果统计。";
            case "batch_job_status" -> "已读取批量任务及逐项状态。失败项会保留错误信息，不影响已完成项目。";
            case "project_summary" -> "已读取项目范围与核心数量摘要；全部数量来自后端持久化事实。";
            case "project_conversations" -> "已读取项目内当前可访问的会话清单、图像状态和更新时间。";
            case "project_vqa_statistics" -> "已由后端确定性统计项目内的题型、答案、置信度、拒答和失败记录；这些数字不是由 Agent 估算。";
            case "batch_result_statistics" -> "已由后端确定性统计批量任务结果和需人工复核案例；Agent 不会自行计数或改写原始预测。";
            case "confidence_distribution" -> "已读取后端确定性置信度分箱和均值。系统不使用全局置信度阈值自动拒答，Agent 也不会把分箱解释为正确率或风险保证。";
            case "unsupported_question_summary" -> "已汇总超范围或拒答数量和可复核样例；这些记录不应被外部模型回答覆盖。";
            case "failed_invocation_summary" -> "已汇总失败调用和错误案例；成功结果保持不变。";
            case "report_draft_data" -> "已读取可用于报告草稿的结构化事实包；保存或导出报告需要独立的受控操作。";
            case "search_knowledge", "knowledge_search" -> "已通过 BGE 与 Milvus 检索知识库；工具输出中的文档、分块和相似度是本次解释的来源，不能替代图像模型推理。";
            case "audit_lookup" -> "已在当前用户可见的审计事件中检索 trace、实体和执行结果。";
            case "create_batch_plan" -> "已按当前图像规模计算容量和问题上限；这只是只读计划，创建任务仍需用户确认。";
            default -> json(output);
        };
    }

    private String argumentsSummary(AgentRequest request, String toolName) {
        return switch (toolName) {
            case "conversation_history", "conversation_vqa_results" -> "{\"conversationId\":\"" + request.conversationId() + "\"}";
            case "batch_job_status" -> "{\"batchJobId\":\"" + request.batchJobId() + "\"}";
            case "project_vqa_statistics", "project_summary", "project_conversations" -> "{\"projectId\":\"" + request.projectId() + "\"}";
            case "batch_result_statistics" -> "{\"batchJobId\":\"" + request.batchJobId() + "\"}";
            case "confidence_distribution", "unsupported_question_summary", "failed_invocation_summary", "create_batch_plan" ->
                    "{\"scopeType\":\"" + scopeType(request) + "\",\"scopeId\":\"" + scopeId(request) + "\"}";
            case "audit_lookup" -> "{\"queryLength\":" + request.message().length() + "}";
            case "report_draft_data" -> request.projectId() == null
                    ? "{\"batchJobId\":\"" + request.batchJobId() + "\"}"
                    : "{\"projectId\":\"" + request.projectId() + "\"}";
            case "single_image_vqa" -> "{\"conversationId\":\"" + request.conversationId() + "\",\"questionLength\":" + request.message().length() + "}";
            default -> "{}";
        };
    }

    private static String scopeType(AgentRequest request) {
        if (request.projectId() != null) return "project";
        if (request.batchJobId() != null) return "batch";
        throw new RequestValidationException("该工具需要 projectId 或 batchJobId。");
    }

    private static String scopeId(AgentRequest request) {
        if (request.projectId() != null) return request.projectId().toString();
        if (request.batchJobId() != null) return request.batchJobId().toString();
        throw new RequestValidationException("该工具需要 projectId 或 batchJobId。");
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private String json(Object value) {
        if (value instanceof String text && (text.startsWith("{") || text.startsWith("["))) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("工具输出无法序列化。", error);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
