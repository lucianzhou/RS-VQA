package com.rsvqa.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final DataSource dataSource;
    private final StringRedisTemplate redis;
    private final VqaService vqa;
    private final WebClient knowledgeClient;

    public SystemStatusController(
            DataSource dataSource,
            StringRedisTemplate redis,
            VqaService vqa,
            @Qualifier("knowledgeServiceClient") WebClient knowledgeClient
    ) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.vqa = vqa;
        this.knowledgeClient = knowledgeClient;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> services = new LinkedHashMap<>();
        services.put("database", checkDatabase());
        services.put("redis", checkRedis());
        services.put("model", checkModel());
        services.put("knowledge", checkKnowledge());
        services.put("agent", Map.of("status", "UP", "mode", "TRUSTED_SINGLE_AGENT", "provider", "UNCONFIGURED"));
        services.put("mcp", Map.of("status", "UP", "mode", "STATELESS_READ_ONLY", "protocol", "MCP"));
        return Map.of("status", "UP", "version", "0.3.0", "services", services);
    }

    private Map<String, Object> checkDatabase() {
        try (var connection = dataSource.getConnection()) {
            return Map.of("status", connection.isValid(2) ? "UP" : "DOWN", "type", "PostgreSQL");
        } catch (Exception error) {
            return Map.of("status", "DOWN", "type", "PostgreSQL");
        }
    }

    private Map<String, Object> checkRedis() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            return Map.of("status", "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN", "role", "task-progress-cache");
        } catch (RuntimeException error) {
            return Map.of("status", "DOWN", "role", "task-progress-cache");
        }
    }

    private Map<String, Object> checkModel() {
        try {
            RuntimeModelInfoResponse model = vqa.currentModel();
            return Map.of(
                    "status", model.ready() ? "UP" : "DOWN",
                    "mode", model.mode(),
                    "releaseId", model.modelReleaseId() == null ? "none" : model.modelReleaseId(),
                    "origin", model.predictionOrigin()
            );
        } catch (RuntimeException error) {
            return Map.of("status", "DOWN", "mode", "unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkKnowledge() {
        try {
            ResponseEntity<Map> response = knowledgeClient.get()
                    .uri("/health")
                    .retrieve()
                    .toEntity(Map.class)
                    .block(Duration.ofSeconds(2));
            return Map.of(
                    "status", response != null && response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN",
                    "embedding", response == null || response.getBody() == null
                            ? "BGE (unavailable)"
                            : String.valueOf(response.getBody().getOrDefault("embedding_model", "BGE"))
            );
        } catch (RuntimeException error) {
            return Map.of("status", "DOWN", "embedding", "BGE / Milvus (RAG profile not running)");
        }
    }
}
