package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_invocation")
public class ModelInvocationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Column(name = "model_release_id", length = 200)
    private String modelReleaseId;

    @Column(name = "provider_type", nullable = false, length = 40)
    private String providerType;

    @Column(name = "prediction_origin", nullable = false, length = 80)
    private String predictionOrigin;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, length = 40)
    private String status;

    private Double confidence;
    private Double margin;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    protected ModelInvocationEntity() {
    }

    public ModelInvocationEntity(
            ConversationEntity conversation,
            String modelReleaseId,
            String providerType,
            String predictionOrigin,
            String question,
            String status,
            Double confidence,
            Double margin,
            Long latencyMs,
            String requestId
    ) {
        this.conversation = conversation;
        this.modelReleaseId = modelReleaseId;
        this.providerType = providerType;
        this.predictionOrigin = predictionOrigin;
        this.question = question;
        this.status = status;
        this.confidence = confidence;
        this.margin = margin;
        this.latencyMs = latencyMs;
        this.requestId = requestId;
    }

    public String getModelReleaseId() {
        return modelReleaseId;
    }

    public String getPredictionOrigin() {
        return predictionOrigin;
    }

    public String getStatus() {
        return status;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Double getMargin() {
        return margin;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getRequestId() {
        return requestId;
    }
}
