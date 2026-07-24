package com.rsvqa.gateway;

import static com.rsvqa.gateway.AgentDtos.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.AgentRunEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
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

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final AgentRunRepository runs;
    private final ToolInvocationRepository toolInvocations;
    private final TrustedAgentTools tools;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Timer runTimer;
    private final Counter runErrors;

    public TrustedAgentService(
            UserRepository users,
            ConversationRepository conversations,
            AgentRunRepository runs,
            ToolInvocationRepository toolInvocations,
            TrustedAgentTools tools,
            AgentToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            MeterRegistry registry
    ) {
        this.users = users;
        this.conversations = conversations;
        this.runs = runs;
        this.toolInvocations = toolInvocations;
        this.tools = tools;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.runTimer = Timer.builder("rsvqa.agent.run")
                .description("Trusted Agent run latency")
                .publishPercentileHistogram()
                .register(registry);
        this.runErrors = Counter.builder("rsvqa.agent.errors")
                .description("Trusted Agent failures")
                .register(registry);
    }

    @Transactional
    public AgentResponse run(AgentRequest request) {
        Timer.Sample metricSample = Timer.start();
        long started = System.nanoTime();
        UserEntity user = currentUser();
        ConversationEntity conversation = request.conversationId() == null ? null
                : conversations.findByIdAndProjectUserId(request.conversationId(), user.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("会话不存在。"));
        AgentRunEntity run = runs.save(new AgentRunEntity(user, conversation, request.message().trim(), TraceId.current()));
        String toolName = chooseTool(request);
        String arguments = argumentsSummary(request, toolName);
        ToolInvocationEntity invocation = toolInvocations.save(new ToolInvocationEntity(run, toolName, arguments));
        long toolStarted = System.nanoTime();
        try {
            Object output = execute(toolName, request);
            long toolLatency = elapsedMillis(toolStarted);
            String outputJson = json(output);
            invocation.complete(outputJson, toolLatency);
            String answer = explain(toolName, output);
            long latency = elapsedMillis(started);
            run.complete(answer, latency);
            return new AgentResponse(
                    run.getId(),
                    run.getStatus(),
                    "UNCONFIGURED_RULE_BASED_TOOL_ORCHESTRATION",
                    answer,
                    run.getTraceId(),
                    latency,
                    List.of(new ToolCallResponse(
                            invocation.getId(), toolName, "COMPLETED", arguments, outputJson, toolLatency
                    )),
                    List.of(),
                    "Agent 仅解释已记录的只读工具或受控 VQA 原始结果；当前未配置外部 LLM，不会生成或改写研究模型答案。"
            );
        } catch (RuntimeException error) {
            runErrors.increment();
            long toolLatency = elapsedMillis(toolStarted);
            String safeMessage = error.getMessage() == null ? "工具调用失败。" : error.getMessage();
            invocation.fail("TOOL_CALL_FAILED", safeMessage, toolLatency);
            long latency = elapsedMillis(started);
            run.fail("TOOL_CALL_FAILED", safeMessage, latency);
            throw error;
        } finally {
            metricSample.stop(runTimer);
        }
    }

    private Object execute(String toolName, AgentRequest request) {
        return switch (toolName) {
            case "current_model_release" -> tools.currentModelRelease();
            case "supported_question_types" -> tools.supportedQuestionTypes();
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
            case "search_knowledge" -> tools.searchKnowledge(request.message().trim());
            default -> throw new RequestValidationException("不在白名单中的 Agent 工具：" + toolName);
        };
    }

    private String chooseTool(AgentRequest request) {
        if (request.toolName() != null && !request.toolName().isBlank()) {
            return request.toolName().trim();
        }
        String message = request.message().toLowerCase(Locale.ROOT);
        if (message.contains("批量") || message.contains("任务")) {
            if (message.contains("统计") || message.contains("分布") || message.contains("汇总")) {
                return "batch_result_statistics";
            }
            return "batch_job_status";
        }
        if ((message.contains("报告") || message.contains("草稿")) && (request.projectId() != null || request.batchJobId() != null)) {
            return "report_draft_data";
        }
        if ((message.contains("项目") || message.contains("统计") || message.contains("分布") || message.contains("汇总"))
                && request.projectId() != null) {
            return "project_vqa_statistics";
        }
        if (message.contains("历史") || message.contains("会话")) {
            return "conversation_history";
        }
        if (message.contains("健康") || message.contains("状态")) {
            return "system_health";
        }
        if (message.contains("支持") || message.contains("范围") || message.contains("类型")) {
            return "supported_question_types";
        }
        if (message.contains("检索") || message.contains("知识") || message.contains("论文") || message.contains("指标")) {
            return "search_knowledge";
        }
        if (request.conversationId() != null
                && (message.contains("图中") || message.contains("图里")
                || message.contains("is there") || message.contains("are there")
                || message.contains("how many") || message.contains("面积"))) {
            return "single_image_vqa";
        }
        return "current_model_release";
    }

    private String explain(String toolName, Object output) {
        return switch (toolName) {
            case "current_model_release" -> {
                RuntimeModelInfoResponse model = (RuntimeModelInfoResponse) output;
                yield "当前模型运行模式为 " + model.mode() + "，发布标识为 "
                        + (model.modelReleaseId() == null ? "未加载" : model.modelReleaseId())
                        + "。该信息来自模型服务的只读版本接口。";
            }
            case "supported_question_types" -> "研究模型面向是否存在、数量、面积和比较等已验证闭集问题；不应当作开放式遥感视觉助手使用。";
            case "system_health" -> "业务服务可用，工具已读取当前模型运行时状态；外部通用模型仍未配置。";
            case "single_image_vqa" -> "已调用当前会话的受控 VQA 主路径并保存模型原始结果。Agent 只说明调用完成，不会改写答案或提升置信度。";
            case "conversation_history" -> "已读取当前登录用户的会话历史。下面的工具输出是持久化记录，不会被当前模型版本覆盖。";
            case "batch_job_status" -> "已读取批量任务及逐项状态。失败项会保留错误信息，不影响已完成项目。";
            case "project_vqa_statistics" -> "已由后端确定性统计项目内的题型、答案、置信度、拒答和失败记录；这些数字不是由 Agent 估算。";
            case "batch_result_statistics" -> "已由后端确定性统计批量任务结果和需人工复核案例；Agent 不会自行计数或改写原始预测。";
            case "report_draft_data" -> "已读取可用于报告草稿的结构化事实包；保存或导出报告需要独立的受控操作。";
            case "search_knowledge" -> "已通过 BGE 与 Milvus 检索知识库；工具输出中的文档、分块和相似度是本次解释的来源，不能替代图像模型推理。";
            default -> json(output);
        };
    }

    private String argumentsSummary(AgentRequest request, String toolName) {
        return switch (toolName) {
            case "conversation_history" -> "{\"conversationId\":\"" + request.conversationId() + "\"}";
            case "batch_job_status" -> "{\"batchJobId\":\"" + request.batchJobId() + "\"}";
            case "project_vqa_statistics" -> "{\"projectId\":\"" + request.projectId() + "\"}";
            case "batch_result_statistics" -> "{\"batchJobId\":\"" + request.batchJobId() + "\"}";
            case "report_draft_data" -> request.projectId() == null
                    ? "{\"batchJobId\":\"" + request.batchJobId() + "\"}"
                    : "{\"projectId\":\"" + request.projectId() + "\"}";
            case "single_image_vqa" -> "{\"conversationId\":\"" + request.conversationId() + "\",\"questionLength\":" + request.message().length() + "}";
            default -> "{}";
        };
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
