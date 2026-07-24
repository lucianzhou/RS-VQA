package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_run")
public class AgentRunEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

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

    protected AgentRunEntity() {
    }

    public AgentRunEntity(UserEntity user, ConversationEntity conversation, String inputText, String traceId) {
        this.user = user;
        this.conversation = conversation;
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

    public String getStatus() {
        return status;
    }

    public String getTraceId() {
        return traceId;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }
}
