package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation")
public class ConversationEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private boolean archived;

    protected ConversationEntity() {
    }

    public ConversationEntity(ProjectEntity project, String title) {
        this.project = project;
        this.title = title;
    }

    public ProjectEntity getProject() {
        return project;
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

    public void moveTo(ProjectEntity project) {
        this.project = project;
    }

    public void archive() {
        archived = true;
    }

    public void restore() {
        archived = false;
    }
}
