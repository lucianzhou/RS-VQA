package com.rsvqa.gateway.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_run")
public class AgentRunEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_session_id")
    private AgentSessionEntity agentSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_job_id")
    private BatchJobEntity batchJob;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @Column(name = "trace_id", nullable = false, length = 100)
    private String traceId;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "provider_id", length = 80)
    private String providerId;

    @Column(name = "provider_model", length = 160)
    private String providerModel;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "estimated_cost_usd", precision = 14, scale = 8)
    private BigDecimal estimatedCostUsd;

    /** Which orchestration produced this run: LLM planning or the rule-based fallback. */
    @Column(name = "provider_state", length = 40)
    private String providerState;

    /** Version of the RS-Bot system prompt and loop contract. */
    @Column(name = "prompt_version", length = 40)
    private String promptVersion;

    /** Why the planning loop stopped: completed, max_steps_reached, timeout, cancelled. */
    @Column(name = "stop_reason", length = 40)
    private String stopReason;

    @Column(name = "tool_steps")
    private Integer toolSteps;

    protected AgentRunEntity() {
    }

    public AgentRunEntity(UserEntity user, ConversationEntity conversation, String inputText, String traceId) {
        this(user, null, null, conversation, null, inputText, traceId);
    }

    public AgentRunEntity(
            UserEntity user,
            AgentSessionEntity agentSession,
            ProjectEntity project,
            ConversationEntity conversation,
            BatchJobEntity batchJob,
            String inputText,
            String traceId
    ) {
        this.user = user;
        this.agentSession = agentSession;
        this.project = project;
        this.conversation = conversation;
        this.batchJob = batchJob;
        this.inputText = inputText;
        this.traceId = traceId;
        this.status = "RUNNING";
    }

    public void complete(String outputText, long latencyMs) {
        this.outputText = outputText;
        this.latencyMs = latencyMs;
        this.status = "COMPLETED";
    }

    public void fail(String code, String outputText, long latencyMs) {
        this.errorCode = code;
        this.outputText = outputText;
        this.latencyMs = latencyMs;
        this.status = "FAILED";
    }

    /**
     * Records who answered and under which instructions.
     *
     * <p>Without this an answer cannot be attributed: the same question can be
     * served by LLM planning or by the deterministic fallback, and the two are
     * not interchangeable evidence.
     */
    public void recordProvenance(
            String providerId,
            String providerModel,
            String providerState,
            String promptVersion,
            String stopReason,
            Integer toolSteps,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens
    ) {
        this.providerId = providerId;
        this.providerModel = providerModel;
        this.providerState = providerState;
        this.promptVersion = promptVersion;
        this.stopReason = stopReason;
        this.toolSteps = toolSteps;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    public void recordEstimatedCost(BigDecimal estimatedCostUsd) {
        this.estimatedCostUsd = estimatedCostUsd;
    }

    public String getProviderState() {
        return providerState;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Integer getToolSteps() {
        return toolSteps;
    }

    public String getStatus() {
        return status;
    }

    public String getTraceId() {
        return traceId;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public AgentSessionEntity getAgentSession() {
        return agentSession;
    }

    public String getInputText() {
        return inputText;
    }

    public String getOutputText() {
        return outputText;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderModel() {
        return providerModel;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public BigDecimal getEstimatedCostUsd() {
        return estimatedCostUsd;
    }
}
