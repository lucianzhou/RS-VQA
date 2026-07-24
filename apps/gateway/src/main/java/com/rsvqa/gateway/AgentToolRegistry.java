package com.rsvqa.gateway;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

@Service
public class AgentToolRegistry {

    private final ControlledVqaAgentTool controlledVqa;
    private final ToolCallback[] callbacks;

    public AgentToolRegistry(TrustedAgentTools readOnlyTools, WorkspaceService workspace) {
        this.controlledVqa = new ControlledVqaAgentTool(workspace);
        this.callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(readOnlyTools, controlledVqa)
                .build()
                .getToolCallbacks();
    }

    public ToolCallback[] callbacks() {
        return callbacks.clone();
    }

    public WorkspaceDtos.QuestionResponse singleImageVqa(String conversationId, String question) {
        return controlledVqa.singleImageVqa(conversationId, question);
    }
}
