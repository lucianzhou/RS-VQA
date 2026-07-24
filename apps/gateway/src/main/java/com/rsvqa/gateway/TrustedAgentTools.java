package com.rsvqa.gateway;

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
    private final ObjectMapper objectMapper;

    public TrustedAgentTools(
            VqaService vqa,
            WorkspaceService workspace,
            BatchService batches,
            KnowledgeService knowledge,
            AnalyticsService analytics,
            ObjectMapper objectMapper
    ) {
        this.vqa = vqa;
        this.workspace = workspace;
        this.batches = batches;
        this.knowledge = knowledge;
        this.analytics = analytics;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("工具输出无法序列化。", error);
        }
    }
}
