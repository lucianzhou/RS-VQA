package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "batch_item")
public class BatchItemEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_job_id", nullable = false)
    private BatchJobEntity batchJob;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "original_name")
    private String originalName;

    @Column(length = 64)
    private String sha256;

    @Column(name = "mime_type", length = 80)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "width_px")
    private Integer widthPx;

    @Column(name = "height_px")
    private Integer heightPx;

    @Column(name = "prediction_origin", length = 80)
    private String predictionOrigin;

    private Double confidence;
    private Double margin;

    @Column(name = "predicted_question_type", length = 40)
    private String predictedQuestionType;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "model_release_id", length = 200)
    private String modelReleaseId;

    @Column(name = "checkpoint_sha256", length = 64)
    private String checkpointSha256;

    @Column(name = "answer_vocabulary_sha256", length = 64)
    private String answerVocabularySha256;

    @Column(name = "runtime_artifact_sha256", length = 64)
    private String runtimeArtifactSha256;

    @Column(name = "top_k_json", columnDefinition = "TEXT")
    private String topKJson;

    @Column(name = "question_type_probabilities_json", columnDefinition = "TEXT")
    private String questionTypeProbabilitiesJson;

    @Column(name = "canonical_question", columnDefinition = "TEXT")
    private String canonicalQuestion;

    @Column(name = "model_input_question", columnDefinition = "TEXT")
    private String modelInputQuestion;

    @Column(name = "question_normalizer_version", length = 40)
    private String questionNormalizerVersion;

    @Column(name = "matched_intent", length = 40)
    private String matchedIntent;

    @Column(name = "question_scope_verification", length = 40)
    private String questionScopeVerification;

    @Column(name = "answer_shape_mismatch", nullable = false)
    private boolean answerShapeMismatch;

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

    @Column(name = "latency_ms")
    private Long latencyMs;

    protected BatchItemEntity() {
    }

    public BatchItemEntity(BatchJobEntity batchJob, FileDescriptor file, String question) {
        this.batchJob = batchJob;
        this.question = question;
        this.status = "QUEUED";
        this.storageKey = file.storageKey();
        this.originalName = file.originalName();
        this.sha256 = file.sha256();
        this.mimeType = file.mimeType();
        this.sizeBytes = file.sizeBytes();
        this.widthPx = file.widthPx();
        this.heightPx = file.heightPx();
    }

    public BatchJobEntity getBatchJob() {
        return batchJob;
    }

    public String getQuestion() {
        return question;
    }

    public String getStatus() {
        return status;
    }

    public String getAnswer() {
        return answer;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSha256() {
        return sha256;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getPredictionOrigin() {
        return predictionOrigin;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Double getMargin() {
        return margin;
    }

    public String getPredictedQuestionType() {
        return predictedQuestionType;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getModelReleaseId() {
        return modelReleaseId;
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

    public String getTopKJson() {
        return topKJson;
    }

    public String getQuestionTypeProbabilitiesJson() {
        return questionTypeProbabilitiesJson;
    }

    public String getCanonicalQuestion() {
        return canonicalQuestion;
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

    public String getQuestionScopeVerification() {
        return questionScopeVerification;
    }

    public boolean isAnswerShapeMismatch() {
        return answerShapeMismatch;
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

    public void start() {
        status = "RUNNING";
        attemptCount++;
        errorCode = null;
        errorMessage = null;
    }

    public void succeed(
            String answer,
            String predictionOrigin,
            Double confidence,
            Double margin,
            String predictedQuestionType,
            String requestId,
            String modelReleaseId,
            String checkpointSha256,
            String answerVocabularySha256,
            String runtimeArtifactSha256,
            String topKJson,
            String questionTypeProbabilitiesJson,
            String canonicalQuestion,
            String modelInputQuestion,
            String questionNormalizerVersion,
            String matchedIntent,
            String questionScopeVerification,
            boolean answerShapeMismatch,
            String taskScope,
            String limitationsJson,
            String capabilityNotice,
            String reviewStatus,
            boolean automaticRejectionEnabled,
            boolean confidenceDisplayEnabled,
            boolean manualReviewSignalEnabled,
            Long latencyMs
    ) {
        status = "COMPLETED";
        this.answer = answer;
        this.predictionOrigin = predictionOrigin;
        this.confidence = confidence;
        this.margin = margin;
        this.predictedQuestionType = predictedQuestionType;
        this.requestId = requestId;
        this.modelReleaseId = modelReleaseId;
        this.checkpointSha256 = checkpointSha256;
        this.answerVocabularySha256 = answerVocabularySha256;
        this.runtimeArtifactSha256 = runtimeArtifactSha256;
        this.topKJson = topKJson;
        this.questionTypeProbabilitiesJson = questionTypeProbabilitiesJson;
        this.canonicalQuestion = canonicalQuestion;
        this.modelInputQuestion = modelInputQuestion;
        this.questionNormalizerVersion = questionNormalizerVersion;
        this.matchedIntent = matchedIntent;
        this.questionScopeVerification = questionScopeVerification;
        this.answerShapeMismatch = answerShapeMismatch;
        this.taskScope = taskScope;
        this.limitationsJson = limitationsJson;
        this.capabilityNotice = capabilityNotice;
        this.reviewStatus = reviewStatus;
        this.automaticRejectionEnabled = automaticRejectionEnabled;
        this.confidenceDisplayEnabled = confidenceDisplayEnabled;
        this.manualReviewSignalEnabled = manualReviewSignalEnabled;
        this.latencyMs = latencyMs;
    }

    public void fail(String code, String message) {
        status = "FAILED";
        errorCode = code;
        errorMessage = message == null ? "处理失败。" : message.substring(0, Math.min(message.length(), 1000));
    }

    public void cancel() {
        if ("QUEUED".equals(status)) {
            status = "CANCELLED";
        }
    }

    public void queueForRetry() {
        if ("FAILED".equals(status)) {
            status = "QUEUED";
            answer = null;
            confidence = null;
            margin = null;
            predictedQuestionType = null;
            requestId = null;
            topKJson = null;
            questionTypeProbabilitiesJson = null;
            canonicalQuestion = null;
            modelInputQuestion = null;
            questionNormalizerVersion = null;
            matchedIntent = null;
            questionScopeVerification = null;
            answerShapeMismatch = false;
            taskScope = null;
            limitationsJson = null;
            capabilityNotice = null;
            reviewStatus = null;
            automaticRejectionEnabled = false;
            confidenceDisplayEnabled = true;
            manualReviewSignalEnabled = true;
            errorCode = null;
            errorMessage = null;
        }
    }

    public record FileDescriptor(
            String storageKey,
            String originalName,
            String sha256,
            String mimeType,
            long sizeBytes,
            int widthPx,
            int heightPx
    ) {
    }
}
