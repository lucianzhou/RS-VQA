package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class ProjectEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false)
    private boolean archived;

    protected ProjectEntity() {
    }

    public ProjectEntity(UserEntity user, String name) {
        this.user = user;
        this.name = name;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public boolean isArchived() {
        return archived;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void archive() {
        archived = true;
    }
}
