package com.rsvqa.gateway;

import static com.rsvqa.gateway.AnalyticsDtos.*;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/projects/{projectId}/statistics")
    public AnalysisStatistics project(@PathVariable UUID projectId) {
        return analytics.project(projectId);
    }

    @GetMapping("/batch-jobs/{jobId}/statistics")
    public AnalysisStatistics batch(@PathVariable UUID jobId) {
        return analytics.batch(jobId);
    }
}
