package com.rsvqa.gateway;

import static com.rsvqa.gateway.KnowledgeDtos.*;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/documents")
    public List<KnowledgeDocumentResponse> documents() {
        return knowledge.list();
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeDocumentResponse upload(@RequestPart("document") MultipartFile document) {
        return knowledge.upload(document);
    }

    @PostMapping("/seed-approved-boundaries")
    public KnowledgeDocumentResponse seed() {
        return knowledge.seedApprovedBoundaries();
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        knowledge.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/search")
    public KnowledgeSearchResponse search(@Valid @RequestBody SearchKnowledgeRequest request) {
        return knowledge.search(request);
    }
}
