package com.rsvqa.gateway;

import static com.rsvqa.gateway.KnowledgeDtos.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import com.rsvqa.gateway.domain.KnowledgeChunkEntity;
import com.rsvqa.gateway.domain.KnowledgeDocumentEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.KnowledgeChunkRepository;
import com.rsvqa.gateway.repository.KnowledgeDocumentRepository;
import com.rsvqa.gateway.repository.UserRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class KnowledgeService {

    private static final long MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private final UserRepository users;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final WebClient knowledgeClient;
    private final KnowledgeServiceProperties properties;
    private final Timer retrievalTimer;
    private final Counter retrievalErrors;

    public KnowledgeService(
            UserRepository users,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            @Qualifier("knowledgeServiceClient") WebClient knowledgeClient,
            KnowledgeServiceProperties properties,
            MeterRegistry registry
    ) {
        this.users = users;
        this.documents = documents;
        this.chunks = chunks;
        this.knowledgeClient = knowledgeClient;
        this.properties = properties;
        this.retrievalTimer = Timer.builder("rsvqa.rag.retrieval")
                .description("BGE and Milvus retrieval latency")
                .publishPercentileHistogram()
                .register(registry);
        this.retrievalErrors = Counter.builder("rsvqa.rag.errors")
                .description("RAG retrieval failures")
                .register(registry);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> list() {
        return documents.findByUserIdOrderByCreatedAtDesc(currentUser().getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public KnowledgeDocumentResponse upload(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new RequestValidationException("知识文档不能为空且不能超过 2 MiB。");
        }
        String name = safeName(file.getOriginalFilename());
        String lower = name.toLowerCase();
        if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw new RequestValidationException("当前知识库只接受 UTF-8 Markdown 或纯文本。");
        }
        try {
            byte[] bytes = file.getBytes();
            String text = decodeUtf8(bytes);
            return indexOrReuse(
                    currentUser(),
                    name,
                    text,
                    sha256(bytes),
                    lower.endsWith(".md") ? "text/markdown" : "text/plain"
            );
        } catch (IOException error) {
            throw new RequestValidationException("无法读取知识文档。");
        }
    }

    @Transactional
    public KnowledgeDocumentResponse seedApprovedBoundaries() {
        try {
            byte[] bytes = new ClassPathResource("knowledge/approved-model-boundaries.md").getInputStream().readAllBytes();
            UserEntity user = currentUser();
            String hash = sha256(bytes);
            return indexOrReuse(
                    user,
                    "RS-VQA 已核准模型边界.md",
                    decodeUtf8(bytes),
                    hash,
                    "text/markdown"
            );
        } catch (IOException error) {
            throw new IllegalStateException("内置知识文档不可读取。", error);
        }
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse search(SearchKnowledgeRequest request) {
        Timer.Sample sample = Timer.start();
        SearchRuntimeRequest runtimeRequest = new SearchRuntimeRequest(
                request.query().trim(),
                request.topK() == null ? 5 : request.topK(),
                request.threshold() == null ? 0.35 : request.threshold(),
                properties.defaultIndexVersion()
        );
        try {
            KnowledgeSearchResponse response = knowledgeClient.post()
                    .uri("/v1/search")
                    .bodyValue(runtimeRequest)
                    .retrieve()
                    .bodyToMono(KnowledgeSearchResponse.class)
                    .block(Duration.ofSeconds(properties.timeoutSeconds()));
            if (response == null) {
                throw new KnowledgeServiceException("知识服务未返回检索结果。", null);
            }
            return response;
        } catch (KnowledgeServiceException error) {
            retrievalErrors.increment();
            throw error;
        } catch (RuntimeException error) {
            retrievalErrors.increment();
            throw new KnowledgeServiceException("BGE/Milvus 检索服务不可用。", error);
        } finally {
            sample.stop(retrievalTimer);
        }
    }

    @Transactional
    public void delete(UUID documentId) {
        KnowledgeDocumentEntity document = documents.findByIdAndUserId(documentId, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在。"));
        try {
            knowledgeClient.delete()
                    .uri("/v1/documents/{id}", documentId)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(properties.timeoutSeconds()));
        } catch (RuntimeException error) {
            throw new KnowledgeServiceException("Milvus 文档删除失败；本地记录未改变。", error);
        }
        chunks.deleteByDocumentId(documentId);
        documents.delete(document);
    }

    private KnowledgeDocumentResponse indexOrReuse(
            UserEntity user,
            String title,
            String text,
            String hash,
            String mimeType
    ) {
        Optional<KnowledgeDocumentEntity> existing = documents.findByUserIdAndSha256(user.getId(), hash);
        if (existing.isPresent() && (
                "READY".equals(existing.get().getStatus()) || "INDEXING".equals(existing.get().getStatus())
        )) {
            return toResponse(existing.get());
        }
        KnowledgeDocumentEntity document = existing.orElseGet(() -> documents.save(new KnowledgeDocumentEntity(
                user, title, hash, mimeType, properties.defaultIndexVersion()
        )));
        if (existing.isPresent()) {
            document.beginIndexing();
            chunks.deleteByDocumentId(document.getId());
        }
        if ("READY".equals(document.getStatus())) {
            return toResponse(document);
        }
        return index(document, user, title, text);
    }

    private KnowledgeDocumentResponse index(
            KnowledgeDocumentEntity document,
            UserEntity user,
            String title,
            String text
    ) {
        List<String> localChunks = chunk(text, 420, 70);
        try {
            IndexRuntimeResponse response = knowledgeClient.post()
                    .uri("/v1/documents")
                    .bodyValue(new IndexRuntimeRequest(
                            document.getId().toString(),
                            title,
                            text,
                            properties.defaultIndexVersion(),
                            Map.of("owner", user.getId().toString(), "source", "user_or_approved_builtin")
                    ))
                    .retrieve()
                    .bodyToMono(IndexRuntimeResponse.class)
                    .block(Duration.ofSeconds(properties.timeoutSeconds()));
            if (response == null || response.chunkCount() != localChunks.size()) {
                throw new KnowledgeServiceException("知识服务索引数量与本地切分不一致。", null);
            }
            for (int index = 0; index < localChunks.size(); index++) {
                chunks.save(new KnowledgeChunkEntity(document, index, localChunks.get(index)));
            }
            document.ready();
        } catch (RuntimeException error) {
            document.fail(error.getMessage());
        }
        return toResponse(document);
    }

    private KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity document) {
        return new KnowledgeDocumentResponse(
                document.getId(), document.getTitle(), document.getSha256(), document.getMimeType(),
                document.getIndexVersion(), document.getStatus(), document.getErrorMessage(), document.getCreatedAt()
        );
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    static List<String> chunk(String text, int size, int overlap) {
        String cleaned = text.replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        String current = "";
        for (String part : cleaned.split("\\n\\n")) {
            String paragraph = part.trim();
            if (paragraph.isBlank()) continue;
            String candidate = current.isBlank() ? paragraph : current + "\n\n" + paragraph;
            if (candidate.length() <= size) {
                current = candidate;
                continue;
            }
            if (!current.isBlank()) raw.add(current);
            while (paragraph.length() > size) {
                raw.add(paragraph.substring(0, size));
                paragraph = paragraph.substring(size - overlap);
            }
            current = paragraph;
        }
        if (!current.isBlank()) raw.add(current);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            result.add(index == 0 || overlap == 0 ? raw.get(index) : raw.get(index - 1).substring(Math.max(0, raw.get(index - 1).length() - overlap)) + "\n" + raw.get(index));
        }
        return result;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new RequestValidationException("知识文档必须是有效 UTF-8 文本。");
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "knowledge.txt";
        String leaf = java.nio.file.Path.of(name).getFileName().toString().replaceAll("[\\p{Cntrl}]", "").trim();
        return leaf.substring(0, Math.min(leaf.length(), 255));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }
}
