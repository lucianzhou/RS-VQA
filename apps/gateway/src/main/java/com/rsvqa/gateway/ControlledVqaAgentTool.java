package com.rsvqa.gateway;

import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Controlled write tool for the formal Agent catalog.
 *
 * <p>This object is intentionally not a Spring bean. It is registered only in
 * {@link AgentToolRegistry}, so the public MCP Server remains read-only.</p>
 */
final class ControlledVqaAgentTool {

    private final WorkspaceService workspace;

    ControlledVqaAgentTool(WorkspaceService workspace) {
        this.workspace = workspace;
    }

    @Tool(
            name = "single_image_vqa",
            description = "在当前登录用户已有图像的会话中调用一次受控 RS-VQA，并保存原始模型结果。需要 conversationId 和问题文本。"
    )
    WorkspaceDtos.QuestionResponse singleImageVqa(
            @ToolParam(description = "已上传图像的会话 UUID", required = true) String conversationId,
            @ToolParam(description = "要交给研究模型的原始问题文本", required = true) String question
    ) {
        return workspace.ask(
                UUID.fromString(conversationId),
                new WorkspaceDtos.QuestionRequest(question, null)
        );
    }
}
