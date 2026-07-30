package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.KnowledgeDocumentEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.KnowledgeChunkRepository;
import com.rsvqa.gateway.repository.KnowledgeDocumentRepository;
import com.rsvqa.gateway.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

class KnowledgeServiceTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("demo", "n/a", List.of())
        );
    }

    @AfterEach
    void stopServer() throws Exception {
        SecurityContextHolder.clearContext();
        server.shutdown();
    }

    @Test
    void chunkingIsDeterministicAndCarriesOverlap() {
        String text = "第一段。".repeat(30) + "\n\n" + "第二段。".repeat(30);
        List<String> chunks = KnowledgeService.chunk(text, 100, 20);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(1)).startsWith(chunks.get(0).substring(chunks.get(0).length() - 20));
        assertThat(chunks).allMatch(chunk -> !chunk.isBlank());
    }

    @Test
    void runtimeSnakeCaseIsTranslatedToPublicCamelCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String runtimeJson = """
                {
                  "request_id": "rag-1",
                  "query": "模型边界",
                  "citations": [{
                    "document_id": "doc-1",
                    "title": "边界",
                    "chunk_index": 0,
                    "content": "闭集分类器",
                    "score": 0.8,
                    "index_version": "v1"
                  }],
                  "latency_ms": 12,
                  "embedding_model": "BAAI/bge-small-zh-v1.5",
                  "collection": "rsvqa_knowledge_v1"
                }
                """;

        KnowledgeDtos.KnowledgeSearchResponse response =
                mapper.readValue(runtimeJson, KnowledgeDtos.KnowledgeSearchResponse.class);
        String publicJson = mapper.writeValueAsString(response);

        assertThat(response.embeddingModel()).isEqualTo("BAAI/bge-small-zh-v1.5");
        assertThat(response.citations().getFirst().chunkIndex()).isZero();
        assertThat(publicJson).contains("\"requestId\":\"rag-1\"");
        assertThat(publicJson).contains("\"embeddingModel\":\"BAAI/bge-small-zh-v1.5\"");
        assertThat(publicJson).contains("\"chunkIndex\":0");
        assertThat(publicJson).doesNotContain("request_id", "embedding_model", "chunk_index");
    }

    @Test
    void searchInjectsAuthenticatedOwnerAndExplicitPublicScope() throws Exception {
        UserRepository users = mock(UserRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        UserEntity user = new UserEntity("demo", "Demo", "USER", true);
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "request_id": "rag-tenant",
                          "query": "用户知识",
                          "citations": [],
                          "latency_ms": 2,
                          "embedding_model": "BAAI/bge-small-zh-v1.5",
                          "collection": "rsvqa_knowledge_v2"
                        }
                        """));
        KnowledgeService service = service(users, documents, chunks);

        service.search(new KnowledgeDtos.SearchKnowledgeRequest("用户知识", 5, 0.0));

        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"owner_id\":\"11111111-1111-1111-1111-111111111111\"");
        assertThat(body).contains("\"include_public\":true");
        assertThat(body).contains("\"index_version\":\"rsvqa-knowledge-v2\"");
        assertThat(body).doesNotContain("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void uploadIndexesPrivateDocumentForAuthenticatedOwner() throws Exception {
        UserRepository users = mock(UserRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        UserEntity user = new UserEntity("demo", "Demo", "USER", true);
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID documentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        when(documents.findByUserIdAndSha256AndIndexVersionAndScope(
                eq(userId), anyString(), eq("rsvqa-knowledge-v2"), eq("PRIVATE")
        )).thenReturn(Optional.empty());
        when(documents.save(any(KnowledgeDocumentEntity.class))).thenAnswer(invocation -> {
            KnowledgeDocumentEntity document = invocation.getArgument(0);
            ReflectionTestUtils.setField(document, "id", documentId);
            return document;
        });
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "document_id": "%s",
                          "index_version": "rsvqa-knowledge-v2",
                          "chunk_count": 1,
                          "embedding_model": "BAAI/bge-small-zh-v1.5",
                          "collection": "rsvqa_knowledge_v2"
                        }
                        """.formatted(documentId)));
        KnowledgeService service = service(users, documents, chunks);

        KnowledgeDtos.KnowledgeDocumentResponse response = service.upload(new MockMultipartFile(
                "document", "private.txt", "text/plain", "用户 A 的私有知识。".getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(response.status()).isEqualTo("READY");
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"owner_id\":\"" + userId + "\"");
        assertThat(body).contains("\"scope\":\"PRIVATE\"");
        assertThat(body).doesNotContain("\"metadata\"");
    }

    @Test
    void deleteIsBoundToAuthenticatedOwnerScopeAndIndexVersion() throws Exception {
        UserRepository users = mock(UserRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        UserEntity user = new UserEntity("demo", "Demo", "USER", true);
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID documentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ReflectionTestUtils.setField(user, "id", userId);
        KnowledgeDocumentEntity document = new KnowledgeDocumentEntity(
                user, "private.txt", "a".repeat(64), "text/plain",
                "rsvqa-knowledge-v2", "PRIVATE"
        );
        ReflectionTestUtils.setField(document, "id", documentId);
        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        when(documents.findByIdAndUserId(documentId, userId)).thenReturn(Optional.of(document));
        server.enqueue(new MockResponse().setResponseCode(204));
        KnowledgeService service = service(users, documents, chunks);

        service.delete(documentId);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("/v1/documents/" + documentId);
        assertThat(request.getRequestUrl().queryParameter("owner_id")).isEqualTo(userId.toString());
        assertThat(request.getRequestUrl().queryParameter("scope")).isEqualTo("PRIVATE");
        assertThat(request.getRequestUrl().queryParameter("index_version")).isEqualTo("rsvqa-knowledge-v2");
        verify(documents).findByIdAndUserId(documentId, userId);
        verify(chunks).deleteByDocumentId(documentId);
    }

    @Test
    void failedApprovedDocumentIsReindexedInPlace() throws Exception {
        UserRepository users = mock(UserRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        UserEntity user = new UserEntity("demo", "Demo", "USER", true);
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        KnowledgeDocumentEntity failed = new KnowledgeDocumentEntity(
                user, "RS-VQA 已核准模型边界.md", "a".repeat(64), "text/markdown",
                "rsvqa-knowledge-v2", "PUBLIC"
        );
        UUID documentId = UUID.randomUUID();
        ReflectionTestUtils.setField(failed, "id", documentId);
        failed.fail("knowledge-service unavailable");

        byte[] approved = new ClassPathResource("knowledge/approved-model-boundaries.md")
                .getInputStream()
                .readAllBytes();
        int chunkCount = KnowledgeService.chunk(
                new String(approved, StandardCharsets.UTF_8), 420, 70
        ).size();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "document_id": "%s",
                          "index_version": "rsvqa-knowledge-v2",
                          "chunk_count": %d,
                          "embedding_model": "BAAI/bge-small-zh-v1.5",
                          "collection": "rsvqa_knowledge_v2"
                        }
                        """.formatted(documentId, chunkCount)));

        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        when(documents.findByScopeAndSha256AndIndexVersion(
                eq("PUBLIC"), anyString(), eq("rsvqa-knowledge-v2")
        )).thenReturn(Optional.of(failed));
        KnowledgeService service = service(users, documents, chunks);

        KnowledgeDtos.KnowledgeDocumentResponse response = service.seedApprovedBoundaries();

        assertThat(response.id()).isEqualTo(documentId);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.errorMessage()).isNull();
        verify(chunks).deleteByDocumentId(documentId);
        verify(documents, never()).save(any());
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/documents");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"scope\":\"PUBLIC\"");
        assertThat(body).contains("\"owner_id\":\"" + userId + "\"");
        assertThat(body).doesNotContain("\"metadata\"");
    }

    @Test
    void readyApprovedDocumentIsReturnedWithoutRemoteReindex() {
        UserRepository users = mock(UserRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        UserEntity user = new UserEntity("demo", "Demo", "USER", true);
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        KnowledgeDocumentEntity ready = new KnowledgeDocumentEntity(
                user, "RS-VQA 已核准模型边界.md", "a".repeat(64), "text/markdown",
                "rsvqa-knowledge-v2", "PUBLIC"
        );
        ReflectionTestUtils.setField(ready, "id", UUID.randomUUID());
        ready.ready();

        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        when(documents.findByScopeAndSha256AndIndexVersion(
                eq("PUBLIC"), anyString(), eq("rsvqa-knowledge-v2")
        )).thenReturn(Optional.of(ready));
        KnowledgeService service = service(users, documents, chunks);

        KnowledgeDtos.KnowledgeDocumentResponse response = service.seedApprovedBoundaries();

        assertThat(response.status()).isEqualTo("READY");
        assertThat(server.getRequestCount()).isZero();
        verify(chunks, never()).deleteByDocumentId(any());
    }

    private KnowledgeService service(
            UserRepository users,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks
    ) {
        return new KnowledgeService(
                users,
                documents,
                chunks,
                WebClient.builder().baseUrl(server.url("/").toString()).build(),
                new KnowledgeServiceProperties(server.url("/").toString(), 2, "rsvqa-knowledge-v2"),
                new SimpleMeterRegistry()
        );
    }
}
