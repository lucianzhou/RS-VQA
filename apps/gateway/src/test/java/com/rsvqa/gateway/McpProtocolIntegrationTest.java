package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

class McpProtocolIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "RSVQA_RUN_MCP_INTEGRATION", matches = "true")
    void discoversAndCallsTheRunningReadOnlyMcpServer() {
        String baseUrl = System.getenv().getOrDefault("RSVQA_MCP_INTEGRATION_URL", "http://127.0.0.1:8088");
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build()) {
            var initialization = client.initialize();
            var tools = client.listTools();
            var result = client.callTool(new CallToolRequest("current_model_release", Map.of()));

            assertThat(initialization.serverInfo().name()).isEqualTo("rs-vqa-readonly-tools");
            assertThat(tools.tools()).extracting(tool -> tool.name())
                    .contains(
                            "current_model_release",
                            "supported_question_types",
                            "system_health",
                            "conversation_history",
                            "batch_job_status",
                            "search_knowledge",
                            "project_summary",
                            "project_conversations",
                            "project_vqa_statistics",
                            "batch_result_statistics",
                            "confidence_distribution",
                            "unsupported_question_summary",
                            "failed_invocation_summary",
                            "knowledge_search",
                            "audit_lookup",
                            "create_batch_plan",
                            "report_draft_data",
                            "conversation_vqa_results",
                            "model_capabilities"
                    )
                    .doesNotContain("single_image_vqa")
                    .hasSize(19);
            assertThat(Boolean.TRUE.equals(result.isError())).isFalse();
            assertThat(result.content()).isNotEmpty();
        }
    }
}
