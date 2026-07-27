package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocumentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false)
    private String title;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(length = 64)
    private String sha256;

    @Column(name = "mime_type", length = 80)
    private String mimeType;

    @Column(name = "index_version", length = 80)
    private String indexVersion;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected KnowledgeDocumentEntity() {
    }

    public KnowledgeDocumentEntity(UserEntity user, String title, String sha256, String mimeType, String indexVersion) {
        this.user = user;
        this.title = title;
        this.sha256 = sha256;
        this.mimeType = mimeType;
        this.indexVersion = indexVersion;
        this.status = "INDEXING";
    }

    public UserEntity getUser() {
        return user;
    }

    public String getTitle() {
        return title;
    }

    public String getSha256() {
        return sha256;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void beginIndexing() {
        status = "INDEXING";
        errorMessage = null;
    }

    public void ready() {
        status = "READY";
        errorMessage = null;
    }

    public void fail(String message) {
        status = "FAILED";
        errorMessage = message == null ? "索引失败。" : message.substring(0, Math.min(message.length(), 1000));
    }
}
