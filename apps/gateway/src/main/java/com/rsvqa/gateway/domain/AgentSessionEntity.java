package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_session")
public class AgentSessionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_job_id")
    private BatchJobEntity batchJob;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private boolean archived;

    protected AgentSessionEntity() {
    }

    public AgentSessionEntity(
            UserEntity user,
            ProjectEntity project,
            ConversationEntity conversation,
            BatchJobEntity batchJob,
            String title
    ) {
        this.user = user;
        this.project = project;
        this.conversation = conversation;
        this.batchJob = batchJob;
        this.title = title;
    }

    public UserEntity getUser() {
        return user;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public BatchJobEntity getBatchJob() {
        return batchJob;
    }

    public String getTitle() {
        return title;
    }

    public boolean isArchived() {
        return archived;
    }

    public void rename(String title) {
        this.title = title;
    }

    public void archive() {
        this.archived = true;
    }

    public void restore() {
        this.archived = false;
    }
}
