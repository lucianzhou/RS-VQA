package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "message")
public class MessageEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_invocation_id")
    private ModelInvocationEntity modelInvocation;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    protected MessageEntity() {
    }

    public MessageEntity(
            ConversationEntity conversation,
            ModelInvocationEntity modelInvocation,
            String role,
            String sourceType,
            String content,
            String metadataJson
    ) {
        this.conversation = conversation;
        this.modelInvocation = modelInvocation;
        this.role = role;
        this.sourceType = sourceType;
        this.content = content;
        this.metadataJson = metadataJson;
    }

    public String getRole() {
        return role;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getContent() {
        return content;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public ModelInvocationEntity getModelInvocation() {
        return modelInvocation;
    }
}
