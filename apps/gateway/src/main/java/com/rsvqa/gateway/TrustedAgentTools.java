package com.rsvqa.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class TrustedAgentTools {

    private final VqaService vqa;
    private final WorkspaceService workspace;
    private final BatchService batches;
    private final KnowledgeService knowledge;
    private final AnalyticsService analytics;
    private final AuditReadService audit;
    private final ObjectMapper objectMapper;

    public TrustedAgentTools(
            VqaService vqa,
            WorkspaceService workspace,
            BatchService batches,
            KnowledgeService knowledge,
            AnalyticsService analytics,
            AuditReadService audit,
            ObjectMapper objectMapper
    ) {
        this.vqa = vqa;
        this.workspace = workspace;
        this.batches = batches;
        this.knowledge = knowledge;
        this.analytics = analytics;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "current_model_release", description = "查询当前固定的 RS-VQA 模型运行模式、发布标识、协议和能力边界。只读。")
    public RuntimeModelInfoResponse currentModelRelease() {
        return vqa.currentModel();
    }

    @Tool(name = "supported_question_types", description = "查询研究模型已验证的问题类型和不支持的能力。只读。")
    public Map<String, Object> supportedQuestionTypes() {
        return Map.of(
                "task", "RSVQA-HR grouped-answer closed-set classification",
                "supported", List.of("presence（是否存在）", "count（数量）", "area（面积）", "comparison（比较）"),
                "input", "图像 + 自由输入的问题文本；部署协议不读取人工 question_type_id",
                "unsupported", List.of("开放式图像描述", "目标检测框", "变化检测", "零样本识别", "风险自动判定"),
                "deploymentProtocol", "qdrop15 + predicted-soft"
        );
    }

    @Tool(name = "model_capabilities", description = "查询研究模型输入协议、闭集问题类型、答案边界和禁止能力。只读。")
    public Map<String, Object> modelCapabilities() {
        return supportedQuestionTypes();
    }

    @Tool(name = "system_health", description = "查询当前 RS-VQA 应用与研究模型运行时的安全健康摘要。只读。")
    public Map<String, Object> systemHealth() {
        RuntimeModelInfoResponse model = vqa.currentModel();
        return Map.of(
                "application", "UP",
                "modelServiceReady", model.ready(),
                "runtimeMode", model.mode(),
                "modelReleaseId", model.modelReleaseId() == null ? "none" : model.modelReleaseId(),
                "externalProvider", "UNCONFIGURED"
        );
    }

    @Tool(name = "conversation_history", description = "按会话标识查询当前登录用户可访问的遥感 VQA 历史。只读。")
    public String conversationHistory(
            @ToolParam(description = "会话 UUID", required = true) String conversationId
    ) {
        return json(workspace.getConversation(UUID.fromString(conversationId)));
    }

    @Tool(name = "conversation_vqa_results", description = "查询会话中已持久化且带模型调用 provenance 的 VQA 结果。只读。")
    public String conversationVqaResults(
            @ToolParam(description = "会话 UUID", required = true) String conversationId
    ) {
        WorkspaceDtos.ConversationResponse conversation = workspace.getConversation(UUID.fromString(conversationId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversation.id());
        result.put("title", conversation.title());
        result.put("image", conversation.image());
        result.put("results", conversation.messages().stream()
                .filter(message -> message.invocation() != null)
                .toList());
        return json(result);
    }

    @Tool(name = "project_summary", description = "查询项目范围、会话数、图像数、调用数和复核数量的确定性摘要。只读。")
    public String projectSummary(
            @ToolParam(description = "项目 UUID", required = true) String projectId
    ) {
        UUID id = UUID.fromString(projectId);
        WorkspaceDtos.ProjectResponse project = project(id);
        AnalyticsDtos.AnalysisStatistics statistics = analytics.project(id);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", project.id());
        summary.put("name", project.name());
        summary.put("conversationCount", statistics.conversationCount());
        summary.put("imageCount", statistics.imageCount());
        summary.put("questionCount", statistics.questionCount());
        summary.put("answeredCount", statistics.answeredCount());
        summary.put("reviewCount", statistics.reviewCases().size());
        summary.put("modelReleaseIds", statistics.modelReleaseIds());
        return json(summary);
    }

    @Tool(name = "project_conversations", description = "查询项目内当前可访问的会话标题、图像状态和更新时间。只读。")
    public String projectConversations(
            @ToolParam(description = "项目 UUID", required = true) String projectId
    ) {
        WorkspaceDtos.ProjectResponse project = project(UUID.fromString(projectId));
        return json(project.conversations());
    }

    @Tool(name = "batch_job_status", description = "按任务标识查询当前登录用户可访问的批量 VQA 状态和逐项结果。只读。")
    public String batchJobStatus(
            @ToolParam(description = "批量任务 UUID", required = true) String batchJobId
    ) {
        return json(batches.get(UUID.fromString(batchJobId)));
    }

    @Tool(name = "project_vqa_statistics", description = "确定性计算当前用户项目内的图像、问题、答案、题型、置信度、拒答和失败分布。只读，LLM 不参与计数。")
    public String projectVqaStatistics(
            @ToolParam(description = "项目 UUID", required = true) String projectId
    ) {
        return json(analytics.project(UUID.fromString(projectId)));
    }

    @Tool(name = "batch_result_statistics", description = "确定性计算批量任务的答案、题型、置信度、拒答、失败和人工复核案例。只读，LLM 不参与计数。")
    public String batchResultStatistics(
            @ToolParam(description = "批量任务 UUID", required = true) String batchJobId
    ) {
        return json(analytics.batch(UUID.fromString(batchJobId)));
    }

    @Tool(name = "confidence_distribution", description = "确定性返回项目或批量任务的置信度分箱和均值。置信度仅描述模型输出分布，不映射为自动风险结论。只读。")
    public String confidenceDistribution(
            @ToolParam(description = "范围类型：project 或 batch", required = true) String scopeType,
            @ToolParam(description = "项目或批任务 UUID", required = true) String scopeId
    ) {
        AnalyticsDtos.AnalysisStatistics statistics = statistics(scopeType, scopeId);
        return json(Map.of(
                "scopeType", statistics.scopeType(),
                "scopeId", statistics.scopeId(),
                "averageConfidence", statistics.averageConfidence() == null ? "none" : statistics.averageConfidence(),
                "automaticRejectionEnabled", statistics.automaticRejectionEnabled(),
                "reviewRecommendedCount", statistics.reviewRecommendedCount(),
                "distribution", statistics.confidenceDistribution()
        ));
    }

    @Tool(name = "unsupported_question_summary", description = "确定性汇总项目或批量任务中的超范围问题数量与可复核样例。只读。")
    public String unsupportedQuestionSummary(
            @ToolParam(description = "范围类型：project 或 batch", required = true) String scopeType,
            @ToolParam(description = "项目或批任务 UUID", required = true) String scopeId
    ) {
        AnalyticsDtos.AnalysisStatistics statistics = statistics(scopeType, scopeId);
        return json(Map.of(
                "scopeType", statistics.scopeType(),
                "scopeId", statistics.scopeId(),
                "unsupportedCount", statistics.unsupportedCount(),
                "examples", statistics.reviewCases().stream()
                        .filter(item -> "unsupported".equalsIgnoreCase(item.status()) || "rejected".equalsIgnoreCase(item.status()))
                        .toList()
        ));
    }

    @Tool(name = "failed_invocation_summary", description = "确定性汇总项目或批量任务中的失败调用数量与错误案例。只读。")
    public String failedInvocationSummary(
            @ToolParam(description = "范围类型：project 或 batch", required = true) String scopeType,
            @ToolParam(description = "项目或批任务 UUID", required = true) String scopeId
    ) {
        AnalyticsDtos.AnalysisStatistics statistics = statistics(scopeType, scopeId);
        return json(Map.of(
                "scopeType", statistics.scopeType(),
                "scopeId", statistics.scopeId(),
                "failedCount", statistics.failedCount(),
                "examples", statistics.reviewCases().stream()
                        .filter(item -> "failed".equalsIgnoreCase(item.status()) || "error".equalsIgnoreCase(item.status()))
                        .toList()
        ));
    }

    @Tool(name = "report_draft_data", description = "读取项目或批量任务的结构化报告事实包。只读；生成报告是独立的受控操作。")
    public String reportDraftData(
            @ToolParam(description = "范围类型：project 或 batch", required = true) String scopeType,
            @ToolParam(description = "项目或批任务 UUID", required = true) String scopeId
    ) {
        return "project".equalsIgnoreCase(scopeType)
                ? json(analytics.project(UUID.fromString(scopeId)))
                : json(analytics.batch(UUID.fromString(scopeId)));
    }

    @Tool(name = "search_knowledge", description = "使用 BGE 向量与 Milvus 检索 RS-VQA 文档，返回带文档和分块标识的来源引用。只读，不用于猜测图像答案。")
    public String searchKnowledge(
            @ToolParam(description = "要检索的知识问题", required = true) String query
    ) {
        return json(knowledge.search(new KnowledgeDtos.SearchKnowledgeRequest(query, 5, 0.35)));
    }

    @Tool(name = "knowledge_search", description = "使用 BGE 与 Milvus 检索有版本的知识来源并返回 citation。只读，不替代 VQA。")
    public String knowledgeSearch(
            @ToolParam(description = "要检索的知识问题", required = true) String query
    ) {
        return searchKnowledge(query);
    }

    @Tool(name = "audit_lookup", description = "在当前用户最近的审计事件中按 trace、实体、事件类型或摘要检索。只读。")
    public String auditLookup(
            @ToolParam(description = "检索词；传 recent 返回最近事件", required = true) String query
    ) {
        return json(audit.lookup(query));
    }

    @Tool(name = "create_batch_plan", description = "根据项目或既有批任务的图像规模计算批量 VQA 容量、分页和问题上限；只生成计划，不创建任务。")
    public String createBatchPlan(
            @ToolParam(description = "范围类型：project 或 batch", required = true) String scopeType,
            @ToolParam(description = "项目或批任务 UUID", required = true) String scopeId
    ) {
        AnalyticsDtos.AnalysisStatistics statistics = statistics(scopeType, scopeId);
        int imageCount = statistics.imageCount();
        int maximumQuestions = imageCount == 0 ? 0 : Math.min(32, 1000 / imageCount);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("scopeType", statistics.scopeType());
        plan.put("scopeId", statistics.scopeId());
        plan.put("imageCount", imageCount);
        plan.put("previewPages", imageCount == 0 ? 0 : (imageCount + 19) / 20);
        plan.put("maximumQuestionsForCurrentImages", maximumQuestions);
        plan.put("limits", Map.of("images", 200, "questions", 32, "combinations", 1000));
        plan.put("candidateQuestionTypes", List.of("presence", "count", "area", "comparison"));
        plan.put("ready", imageCount > 0);
        plan.put("notice", imageCount > 0
                ? "该输出仅是容量与题型计划；创建批量任务仍需用户确认。"
                : "当前范围没有可用图像，不能创建批量计划。");
        return json(plan);
    }

    private WorkspaceDtos.ProjectResponse project(UUID projectId) {
        return workspace.listProjects().stream()
                .filter(item -> item.id().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在或已归档。"));
    }

    private AnalyticsDtos.AnalysisStatistics statistics(String scopeType, String scopeId) {
        UUID id = UUID.fromString(scopeId);
        if ("project".equalsIgnoreCase(scopeType)) return analytics.project(id);
        if ("batch".equalsIgnoreCase(scopeType) || "batch_job".equalsIgnoreCase(scopeType)) {
            return analytics.batch(id);
        }
        throw new RequestValidationException("范围类型必须是 project 或 batch。");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("工具输出无法序列化。", error);
        }
    }
}
