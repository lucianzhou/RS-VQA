package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class KnowledgeServiceTest {

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
}
