package com.rsvqa.gateway.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "agent_action_proposal")
public class AgentActionProposalEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_session_id")
    private AgentSessionEntity agentSession;

    @Column(name = "action_name", nullable = false, length = 80)
    private String actionName;

    @Column(name = "arguments_json", nullable = false, columnDefinition = "TEXT")
    private String argumentsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "request_id", nullable = false, unique = true, length = 100)
    private String requestId;

    @Column(name = "provider_id", nullable = false, length = 80)
    private String providerId;

    @Column(name = "provider_model", length = 160)
    private String providerModel;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 14, scale = 8)
    private BigDecimal estimatedCostUsd;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AgentActionProposalEntity() {
    }

    public AgentActionProposalEntity(
            UserEntity user,
            AgentSessionEntity agentSession,
            String actionName,
            String argumentsJson,
            String summary,
            String requestId,
            Instant expiresAt
    ) {
        this.user = user;
        this.agentSession = agentSession;
        this.actionName = actionName;
        this.argumentsJson = argumentsJson;
        this.summary = summary;
        this.requestId = requestId;
        this.expiresAt = expiresAt;
        this.status = "PENDING";
        this.providerId = "deterministic_action_controller";
        this.providerModel = "none";
        this.estimatedCostUsd = BigDecimal.ZERO;
    }

    public void confirm() {
        if (!"PENDING".equals(status)) return;
        if (Instant.now().isAfter(expiresAt)) {
            status = "EXPIRED";
            throw new IllegalStateException("ACTION_PROPOSAL_EXPIRED");
        }
        confirmedAt = Instant.now();
        status = "EXECUTING";
    }

    public void complete(String resultJson) {
        this.resultJson = resultJson;
        this.executedAt = Instant.now();
        this.status = "COMPLETED";
    }

    public void fail(String errorCode, String resultJson) {
        this.errorCode = errorCode;
        this.resultJson = resultJson;
        this.executedAt = Instant.now();
        this.status = "FAILED";
    }

    public void reject() {
        if (!"PENDING".equals(status)) return;
        status = "REJECTED";
    }

    public UserEntity getUser() { return user; }
    public AgentSessionEntity getAgentSession() { return agentSession; }
    public String getActionName() { return actionName; }
    public String getArgumentsJson() { return argumentsJson; }
    public String getSummary() { return summary; }
    public String getStatus() { return status; }
    public String getRequestId() { return requestId; }
    public String getProviderId() { return providerId; }
    public String getProviderModel() { return providerModel; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return totalTokens; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public String getResultJson() { return resultJson; }
    public String getErrorCode() { return errorCode; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
