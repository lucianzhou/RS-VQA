package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunkEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocumentEntity document;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "vector_id", length = 200)
    private String vectorId;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    protected KnowledgeChunkEntity() {
    }

    public KnowledgeChunkEntity(KnowledgeDocumentEntity document, int ordinal, String content) {
        this.document = document;
        this.ordinal = ordinal;
        this.content = content;
        this.vectorId = document.getId() + ":" + ordinal;
        this.metadataJson = "{}";
    }
}
