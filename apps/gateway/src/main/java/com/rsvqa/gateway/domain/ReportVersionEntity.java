package com.rsvqa.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "report_version")
public class ReportVersionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private ReportEntity report;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "facts_json", nullable = false, columnDefinition = "TEXT")
    private String factsJson;

    @Column(name = "markdown_content", nullable = false, columnDefinition = "TEXT")
    private String markdownContent;

    @Column(name = "agent_summary", columnDefinition = "TEXT")
    private String agentSummary;

    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "model_release_id", length = 200)
    private String modelReleaseId;

    @Column(name = "prediction_origin", nullable = false, length = 80)
    private String predictionOrigin;

    @Column(name = "generated_by", nullable = false, length = 80)
    private String generatedBy;

    protected ReportVersionEntity() {
    }

    public ReportVersionEntity(
            ReportEntity report,
            int versionNumber,
            String factsJson,
            String markdownContent,
            String agentSummary,
            String citationsJson,
            String modelReleaseId,
            String predictionOrigin,
            String generatedBy
    ) {
        this.report = report;
        this.versionNumber = versionNumber;
        this.factsJson = factsJson;
        this.markdownContent = markdownContent;
        this.agentSummary = agentSummary;
        this.citationsJson = citationsJson;
        this.modelReleaseId = modelReleaseId;
        this.predictionOrigin = predictionOrigin;
        this.generatedBy = generatedBy;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getFactsJson() {
        return factsJson;
    }

    public String getMarkdownContent() {
        return markdownContent;
    }

    public String getAgentSummary() {
        return agentSummary;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public String getModelReleaseId() {
        return modelReleaseId;
    }

    public String getPredictionOrigin() {
        return predictionOrigin;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }
}
