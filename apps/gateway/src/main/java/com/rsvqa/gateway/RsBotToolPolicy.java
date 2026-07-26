package com.rsvqa.gateway;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which tools RS-Bot may call in a given session context.
 *
 * <p>The model never sees the full catalogue. A conversation-scoped session has
 * no project statistics to summarise and a project-scoped session has no single
 * image to ask about, so offering those tools only invites the model to invent
 * an ID and call them. The whitelist is computed from the session's own binding,
 * and the loop rejects anything outside it even if the model asks — a model that
 * hallucinates a tool name must be refused, not trusted.
 */
final class RsBotToolPolicy {

    /** Available in every context: they answer questions about the system itself. */
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "current_model_release",
            "supported_question_types",
            "model_capabilities",
            "system_health",
            "search_knowledge",
            "knowledge_search",
            "audit_lookup"
    );

    private static final Set<String> CONVERSATION_TOOLS = Set.of(
            "conversation_history",
            "conversation_vqa_results",
            "single_image_vqa"
    );

    private static final Set<String> PROJECT_TOOLS = Set.of(
            "project_summary",
            "project_conversations",
            "project_vqa_statistics",
            "confidence_distribution",
            "unsupported_question_summary",
            "failed_invocation_summary",
            "report_draft_data",
            "create_batch_plan"
    );

    private static final Set<String> BATCH_TOOLS = Set.of(
            "batch_job_status",
            "batch_result_statistics",
            "confidence_distribution",
            "unsupported_question_summary",
            "failed_invocation_summary",
            "report_draft_data",
            "create_batch_plan"
    );

    /**
     * Read-only by construction: no tool in any list above writes user data.
     * Writes go through {@link AgentActionService} proposals, which require an
     * explicit human confirmation and are never reachable from this loop.
     */
    private RsBotToolPolicy() {
    }

    static Set<String> allowedFor(AgentDtos.AgentRequest request) {
        Set<String> allowed = new LinkedHashSet<>(ALWAYS_ALLOWED);
        if (request.conversationId() != null) {
            allowed.addAll(CONVERSATION_TOOLS);
        }
        if (request.projectId() != null) {
            allowed.addAll(PROJECT_TOOLS);
        }
        if (request.batchJobId() != null) {
            allowed.addAll(BATCH_TOOLS);
        }
        return Set.copyOf(allowed);
    }
}
