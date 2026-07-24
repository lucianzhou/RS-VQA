package com.rsvqa.gateway.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "report")
public class ReportEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_job_id")
    private BatchJobEntity batchJob;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "report_type", nullable = false, length = 40)
    private String reportType;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "request_id", nullable = false, length = 100)
    private String requestId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected ReportEntity() {
    }

    public ReportEntity(
            UserEntity user,
            ProjectEntity project,
            BatchJobEntity batchJob,
            String title,
            String reportType,
            String requestId
    ) {
        this.user = user;
        this.project = project;
        this.batchJob = batchJob;
        this.title = title;
        this.reportType = reportType;
        this.requestId = requestId;
        this.status = "DRAFT";
        this.currentVersion = 1;
    }

    public UserEntity getUser() {
        return user;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public BatchJobEntity getBatchJob() {
        return batchJob;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public String getReportType() {
        return reportType;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void advanceVersion() {
        currentVersion++;
        status = "DRAFT";
        confirmedAt = null;
    }

    public void confirm() {
        status = "CONFIRMED";
        confirmedAt = Instant.now();
    }
}
