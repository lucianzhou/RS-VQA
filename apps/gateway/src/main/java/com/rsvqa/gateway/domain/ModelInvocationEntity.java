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

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(nullable = false, length = 40)
    private String status;

    private Double confidence;
    private Double margin;

    @Column(name = "predicted_question_type", length = 40)
    private String predictedQuestionType;

    @Column(name = "top_k_json", columnDefinition = "TEXT")
    private String topKJson;

    @Column(name = "question_type_probabilities_json", columnDefinition = "TEXT")
    private String questionTypeProbabilitiesJson;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "provider_model", length = 160)
    private String providerModel;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "estimated_cost_usd", precision = 14, scale = 8)
    private java.math.BigDecimal estimatedCostUsd;

    protected ModelInvocationEntity() {
    }

    public ModelInvocationEntity(
            ConversationEntity conversation,
            String modelReleaseId,
            String providerType,
            String predictionOrigin,
            String question,
            String answer,
            String status,
            Double confidence,
            Double margin,
            String predictedQuestionType,
            String topKJson,
            String questionTypeProbabilitiesJson,
            Long latencyMs,
            String requestId
    ) {
        this.conversation = conversation;
        this.modelReleaseId = modelReleaseId;
        this.providerType = providerType;
        this.predictionOrigin = predictionOrigin;
        this.question = question;
        this.answer = answer;
        this.status = status;
        this.confidence = confidence;
        this.margin = margin;
        this.predictedQuestionType = predictedQuestionType;
        this.topKJson = topKJson;
        this.questionTypeProbabilitiesJson = questionTypeProbabilitiesJson;
        this.latencyMs = latencyMs;
        this.requestId = requestId;
    }

    public ModelInvocationEntity(
            ConversationEntity conversation,
            String modelReleaseId,
            String providerType,
            String providerModel,
            String predictionOrigin,
            String question,
            String answer,
            String status,
            Double confidence,
            Double margin,
            String predictedQuestionType,
            String topKJson,
            String questionTypeProbabilitiesJson,
            Long latencyMs,
            String requestId,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Double estimatedCostUsd
    ) {
        this(
                conversation, modelReleaseId, providerType, predictionOrigin, question, answer, status,
                confidence, margin, predictedQuestionType, topKJson, questionTypeProbabilitiesJson,
                latencyMs, requestId
        );
        this.providerModel = providerModel;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.estimatedCostUsd = estimatedCostUsd == null ? null : java.math.BigDecimal.valueOf(estimatedCostUsd);
    }

    public String getModelReleaseId() {
        return modelReleaseId;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public String getPredictionOrigin() {
        return predictionOrigin;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
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

    public String getPredictedQuestionType() {
        return predictedQuestionType;
    }

    public String getTopKJson() {
        return topKJson;
    }

    public String getQuestionTypeProbabilitiesJson() {
        return questionTypeProbabilitiesJson;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getRequestId() {
        return requestId;
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

    public Double getEstimatedCostUsd() {
        return estimatedCostUsd == null ? null : estimatedCostUsd.doubleValue();
    }
}
