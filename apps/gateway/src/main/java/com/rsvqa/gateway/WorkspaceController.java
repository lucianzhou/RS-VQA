package com.rsvqa.gateway;

import static com.rsvqa.gateway.WorkspaceDtos.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    public List<ProjectResponse> projects() {
        return service.listProjects();
    }

    @PostMapping("/projects")
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return service.createProject(request);
    }

    @PostMapping("/projects/{projectId}/conversations")
    public ConversationResponse createConversation(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return service.createConversation(projectId, request);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationResponse conversation(@PathVariable UUID conversationId) {
        return service.getConversation(conversationId);
    }

    @PostMapping(value = "/conversations/{conversationId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageResponse uploadImage(
            @PathVariable UUID conversationId,
            @RequestPart("image") MultipartFile image
    ) {
        return service.uploadImage(conversationId, image);
    }

    @DeleteMapping("/conversations/{conversationId}/image")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID conversationId) {
        service.deleteImage(conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/image/content")
    public ResponseEntity<ByteArrayResource> imageContent(@PathVariable UUID conversationId) {
        WorkspaceService.ImageContent image = service.imageContent(conversationId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(image.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(new ByteArrayResource(image.bytes()));
    }

    @GetMapping("/conversations/{conversationId}/report")
    public ResponseEntity<ByteArrayResource> report(@PathVariable UUID conversationId) {
        WorkspaceService.ReportContent report = service.report(conversationId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(report.name(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(new ByteArrayResource(report.bytes()));
    }

    @PostMapping("/conversations/{conversationId}/questions")
    public QuestionResponse ask(
            @PathVariable UUID conversationId,
            @Valid @RequestBody QuestionRequest request
    ) {
        return service.ask(conversationId, request);
    }
}
