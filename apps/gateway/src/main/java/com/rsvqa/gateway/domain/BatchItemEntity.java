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

    public Double getConfidence() {
        return confidence;
    }

    public String getPredictionOrigin() {
        return predictionOrigin;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void start() {
        status = "RUNNING";
        attemptCount++;
        errorCode = null;
        errorMessage = null;
    }

    public void succeed(String answer, String predictionOrigin, Double confidence, Long latencyMs) {
        status = "COMPLETED";
        this.answer = answer;
        this.predictionOrigin = predictionOrigin;
        this.confidence = confidence;
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
