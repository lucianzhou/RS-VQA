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

    @Column(name = "checkpoint_sha256", length = 64)
    private String checkpointSha256;

    @Column(name = "answer_vocabulary_sha256", length = 64)
    private String answerVocabularySha256;

    @Column(name = "runtime_artifact_sha256", length = 64)
    private String runtimeArtifactSha256;

    @Column(name = "input_sha256", length = 64)
    private String inputSha256;

    @Column(name = "task_scope", length = 200)
    private String taskScope;

    @Column(name = "limitations_json", columnDefinition = "TEXT")
    private String limitationsJson;

    @Column(name = "capability_notice", columnDefinition = "TEXT")
    private String capabilityNotice;

    @Column(name = "review_status", length = 80)
    private String reviewStatus;

    @Column(name = "automatic_rejection_enabled", nullable = false)
    private boolean automaticRejectionEnabled;

    @Column(name = "confidence_display_enabled", nullable = false)
    private boolean confidenceDisplayEnabled = true;

    @Column(name = "manual_review_signal_enabled", nullable = false)
    private boolean manualReviewSignalEnabled = true;

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

    // Question-normalization audit. `question` above always holds the verbatim
    // user text; these record what the research model was actually asked and by
    // which normalizer, so a stored answer stays replayable. They are populated
    // only through recordQuestionNormalization, which the external-provider path
    // never calls.

    @Column(name = "canonical_question", columnDefinition = "TEXT")
    private String canonicalQuestion;

    @Column(name = "canonical_question_display", columnDefinition = "TEXT")
    private String canonicalQuestionDisplay;

    @Column(name = "model_input_question", columnDefinition = "TEXT")
    private String modelInputQuestion;

    @Column(name = "question_normalizer_version", length = 40)
    private String questionNormalizerVersion;

    @Column(name = "matched_intent", length = 40)
    private String matchedIntent;

    @Column(name = "matched_objects_json", columnDefinition = "TEXT")
    private String matchedObjectsJson;

    @Column(name = "question_scope_verification", length = 40)
    private String questionScopeVerification;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    @Column(name = "needs_clarification", nullable = false)
    private boolean needsClarification;

    @Column(name = "clarification_options_json", columnDefinition = "TEXT")
    private String clarificationOptionsJson;

    @Column(name = "display_answer", columnDefinition = "TEXT")
    private String displayAnswer;

    @Column(name = "display_locale", length = 20)
    private String displayLocale;

    @Column(name = "answer_shape_mismatch", nullable = false)
    private boolean answerShapeMismatch;

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

    public void recordResearchProvenance(
            String checkpointSha256,
            String answerVocabularySha256,
            String runtimeArtifactSha256
    ) {
        this.checkpointSha256 = checkpointSha256;
        this.answerVocabularySha256 = answerVocabularySha256;
        this.runtimeArtifactSha256 = runtimeArtifactSha256;
    }

    public void recordInferenceContract(
            String inputSha256,
            String taskScope,
            String limitationsJson,
            String capabilityNotice,
            String reviewStatus,
            boolean automaticRejectionEnabled,
            boolean confidenceDisplayEnabled,
            boolean manualReviewSignalEnabled
    ) {
        this.inputSha256 = inputSha256;
        this.taskScope = taskScope;
        this.limitationsJson = limitationsJson;
        this.capabilityNotice = capabilityNotice;
        this.reviewStatus = reviewStatus;
        this.automaticRejectionEnabled = automaticRejectionEnabled;
        this.confidenceDisplayEnabled = confidenceDisplayEnabled;
        this.manualReviewSignalEnabled = manualReviewSignalEnabled;
    }

    /**
     * Records how the raw question was normalized before inference.
     *
     * <p>Only the research-model path calls this. External vision providers keep
     * these columns NULL, which is what makes "this answer was canonicalized"
     * verifiable from the database alone.
     */
    public void recordQuestionNormalization(
            String canonicalQuestion,
            String canonicalQuestionDisplay,
            String modelInputQuestion,
            String questionNormalizerVersion,
            String matchedIntent,
            String matchedObjectsJson,
            String questionScopeVerification,
            String reasonCode,
            boolean needsClarification,
            String clarificationOptionsJson,
            String displayAnswer,
            String displayLocale,
            boolean answerShapeMismatch
    ) {
        this.canonicalQuestion = canonicalQuestion;
        this.canonicalQuestionDisplay = canonicalQuestionDisplay;
        this.modelInputQuestion = modelInputQuestion;
        this.questionNormalizerVersion = questionNormalizerVersion;
        this.matchedIntent = matchedIntent;
        this.matchedObjectsJson = matchedObjectsJson;
        this.questionScopeVerification = questionScopeVerification;
        this.reasonCode = reasonCode;
        this.needsClarification = needsClarification;
        this.clarificationOptionsJson = clarificationOptionsJson;
        this.displayAnswer = displayAnswer;
        this.displayLocale = displayLocale;
        this.answerShapeMismatch = answerShapeMismatch;
    }

    public String getCanonicalQuestion() {
        return canonicalQuestion;
    }

    public String getCanonicalQuestionDisplay() {
        return canonicalQuestionDisplay;
    }

    public String getModelInputQuestion() {
        return modelInputQuestion;
    }

    public String getQuestionNormalizerVersion() {
        return questionNormalizerVersion;
    }

    public String getMatchedIntent() {
        return matchedIntent;
    }

    public String getMatchedObjectsJson() {
        return matchedObjectsJson;
    }

    public String getQuestionScopeVerification() {
        return questionScopeVerification;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public boolean isNeedsClarification() {
        return needsClarification;
    }

    public String getClarificationOptionsJson() {
        return clarificationOptionsJson;
    }

    public String getDisplayAnswer() {
        return displayAnswer;
    }

    public String getDisplayLocale() {
        return displayLocale;
    }

    public boolean isAnswerShapeMismatch() {
        return answerShapeMismatch;
    }

    public String getCheckpointSha256() {
        return checkpointSha256;
    }

    public String getAnswerVocabularySha256() {
        return answerVocabularySha256;
    }

    public String getRuntimeArtifactSha256() {
        return runtimeArtifactSha256;
    }

    public String getInputSha256() {
        return inputSha256;
    }

    public String getTaskScope() {
        return taskScope;
    }

    public String getLimitationsJson() {
        return limitationsJson;
    }

    public String getCapabilityNotice() {
        return capabilityNotice;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public boolean isAutomaticRejectionEnabled() {
        return automaticRejectionEnabled;
    }

    public boolean isConfidenceDisplayEnabled() {
        return confidenceDisplayEnabled;
    }

    public boolean isManualReviewSignalEnabled() {
        return manualReviewSignalEnabled;
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
