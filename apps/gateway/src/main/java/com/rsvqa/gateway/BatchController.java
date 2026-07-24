package com.rsvqa.gateway;

import static com.rsvqa.gateway.BatchDtos.*;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/batch-jobs")
public class BatchController {

    private final BatchService batches;
    private final BatchWorker worker;

    public BatchController(BatchService batches, BatchWorker worker) {
        this.batches = batches;
        this.worker = worker;
    }

    @GetMapping
    public List<BatchJobResponse> list() {
        return batches.list();
    }

    @GetMapping("/archive")
    public List<BatchJobResponse> archive() {
        return batches.archive();
    }

    @GetMapping("/{jobId}")
    public BatchJobResponse get(@PathVariable UUID jobId) {
        return batches.get(jobId);
    }

    @GetMapping(value = "/{jobId}/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@PathVariable UUID jobId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"batch-" + jobId + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(batches.exportCsv(jobId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchJobResponse create(
            @RequestPart("images") List<MultipartFile> images,
            @RequestParam("questions") List<String> questions,
            @RequestParam(value = "projectId", required = false) UUID projectId,
            @RequestParam(value = "modelReleaseId", required = false) String modelReleaseId
    ) {
        BatchJobResponse response = batches.create(projectId, modelReleaseId, images, questions);
        worker.process(response.id());
        return response;
    }

    @PostMapping("/{jobId}/cancel")
    public BatchJobResponse cancel(@PathVariable UUID jobId) {
        return batches.cancel(jobId);
    }

    @PostMapping("/{jobId}/retry-failed")
    public BatchJobResponse retryFailed(@PathVariable UUID jobId) {
        BatchJobResponse response = batches.retryFailed(jobId);
        worker.process(jobId);
        return response;
    }

    @PostMapping("/{jobId}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID jobId) {
        batches.archive(jobId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID jobId) {
        batches.restore(jobId);
        return ResponseEntity.noContent().build();
    }
}
