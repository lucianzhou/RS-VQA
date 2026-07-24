package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class UserEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    private boolean demo;

    protected UserEntity() {
    }

    public UserEntity(String username, String displayName, String role, boolean demo) {
        this(username, null, displayName, role, demo);
    }

    public UserEntity(String username, String passwordHash, String displayName, String role, boolean demo) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.demo = demo;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public boolean isDemo() {
        return demo;
    }
}
