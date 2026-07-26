package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tool_invocation")
public class ToolInvocationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false)
    private AgentRunEntity agentRun;

    @Column(name = "tool_name", nullable = false, length = 120)
    private String toolName;

    @Column(name = "arguments_summary", columnDefinition = "TEXT")
    private String argumentsSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    protected ToolInvocationEntity() {
    }

    public ToolInvocationEntity(AgentRunEntity agentRun, String toolName, String argumentsSummary) {
        this.agentRun = agentRun;
        this.toolName = toolName;
        this.argumentsSummary = argumentsSummary;
        this.status = "RUNNING";
    }

    public void complete(String output, long latencyMs) {
        this.outputSummary = truncate(output);
        this.latencyMs = latencyMs;
        this.status = "COMPLETED";
    }

    public void fail(String errorCode, String output, long latencyMs) {
        this.outputSummary = truncate(output);
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
        this.status = "FAILED";
    }

    public void reject(String errorCode, String output, long latencyMs) {
        this.outputSummary = truncate(output);
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
        this.status = "REJECTED";
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), 4000));
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsSummary() {
        return argumentsSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public String getStatus() {
        return status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
