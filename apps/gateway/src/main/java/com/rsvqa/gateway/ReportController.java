package com.rsvqa.gateway;

import static com.rsvqa.gateway.ReportDtos.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public List<ReportSummary> list() {
        return reports.list();
    }

    @PostMapping
    public ReportResponse create(@Valid @RequestBody CreateReportRequest request) {
        return reports.create(request);
    }

    @GetMapping("/{reportId}")
    public ReportResponse get(@PathVariable UUID reportId) {
        return reports.get(reportId);
    }

    @PostMapping("/{reportId}/versions")
    public ReportResponse regenerate(@PathVariable UUID reportId) {
        return reports.regenerate(reportId);
    }

    @PostMapping("/{reportId}/confirm")
    public ReportResponse confirm(@PathVariable UUID reportId) {
        return reports.confirm(reportId);
    }

    @GetMapping("/{reportId}/export")
    public ResponseEntity<ByteArrayResource> export(
            @PathVariable UUID reportId,
            @RequestParam(defaultValue = "md") String format
    ) {
        ReportService.ExportContent content = reports.export(reportId, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType() + ";charset=UTF-8"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(new ByteArrayResource(content.bytes()));
    }
}
