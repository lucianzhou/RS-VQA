package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "image_asset")
public class ImageAssetEntity extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, unique = true)
    private ConversationEntity conversation;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "width_px", nullable = false)
    private int widthPx;

    @Column(name = "height_px", nullable = false)
    private int heightPx;

    protected ImageAssetEntity() {
    }

    public ImageAssetEntity(
            ConversationEntity conversation,
            String storageKey,
            String originalName,
            String sha256,
            String mimeType,
            long sizeBytes,
            int widthPx,
            int heightPx
    ) {
        this.conversation = conversation;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.sha256 = sha256;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getSha256() {
        return sha256;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getWidthPx() {
        return widthPx;
    }

    public int getHeightPx() {
        return heightPx;
    }
}
