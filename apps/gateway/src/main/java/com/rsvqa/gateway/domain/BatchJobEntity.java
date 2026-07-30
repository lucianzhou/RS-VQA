package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "batch_job")
public class BatchJobEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @Column(name = "model_release_id", length = 200)
    private String modelReleaseId;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "completed_items", nullable = false)
    private int completedItems;

    @Column(name = "failed_items", nullable = false)
    private int failedItems;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(nullable = false)
    private boolean archived;

    protected BatchJobEntity() {
    }

    public BatchJobEntity(UserEntity user, ProjectEntity project, String modelReleaseId, int totalItems) {
        this.user = user;
        this.project = project;
        this.modelReleaseId = modelReleaseId;
        this.totalItems = totalItems;
        this.status = "QUEUED";
    }

    public UserEntity getUser() {
        return user;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public String getModelReleaseId() {
        return modelReleaseId;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public int getCompletedItems() {
        return completedItems;
    }

    public int getFailedItems() {
        return failedItems;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        archived = true;
    }

    public void restore() {
        archived = false;
    }

    public void requestCancel() {
        cancelRequested = true;
        if ("QUEUED".equals(status)) {
            status = "CANCELLED";
        }
    }

    public void retry(int count) {
        completedItems = Math.max(0, completedItems - count);
        failedItems = Math.max(0, failedItems - count);
        cancelRequested = false;
        status = "QUEUED";
    }
}
