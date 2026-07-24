package com.rsvqa.gateway;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Optional Spring AI MCP Client boundary.
 *
 * <p>The formal application can discover and invoke configured remote MCP
 * servers without coupling business services to an MCP SDK. The client is
 * disabled by default and only exposes the project's reviewed read-only tool
 * names.</p>
 */
@Service
public class McpClientBoundaryService {

    private static final Set<String> READ_ONLY_ALLOWLIST = Set.of(
            "current_model_release",
            "supported_question_types",
            "model_capabilities",
            "system_health",
            "conversation_history",
            "conversation_vqa_results",
            "project_summary",
            "project_conversations",
            "project_vqa_statistics",
            "batch_job_status",
            "batch_result_statistics",
            "confidence_distribution",
            "unsupported_question_summary",
            "failed_invocation_summary",
            "report_draft_data",
            "search_knowledge",
            "knowledge_search",
            "audit_lookup",
            "create_batch_plan"
    );

    private final ObjectProvider<List<McpSyncClient>> clients;

    public McpClientBoundaryService(ObjectProvider<List<McpSyncClient>> clients) {
        this.clients = clients;
    }

    public McpClientStatus status() {
        List<McpSyncClient> configured = configuredClients();
        return new McpClientStatus(!configured.isEmpty(), configured.size(), "SYNC", "READ_ONLY_ALLOWLIST");
    }

    public List<McpRemoteTool> discoverTools() {
        return requiredClient().listTools().tools().stream()
                .filter(tool -> READ_ONLY_ALLOWLIST.contains(tool.name()))
                .map(tool -> new McpRemoteTool(
                        tool.name(),
                        tool.title(),
                        tool.description(),
                        tool.inputSchema(),
                        true
                ))
                .toList();
    }

    public McpRemoteCallResult call(String toolName, Map<String, Object> arguments) {
        if (!READ_ONLY_ALLOWLIST.contains(toolName)) {
            throw new RequestValidationException("MCP 工具不在只读白名单中。");
        }
        try {
            CallToolResult result = requiredClient().callTool(new CallToolRequest(
                    toolName,
                    arguments == null ? Map.of() : Map.copyOf(arguments)
            ));
            return new McpRemoteCallResult(
                    toolName,
                    !Boolean.TRUE.equals(result.isError()),
                    result.isError(),
                    result.content(),
                    result.structuredContent()
            );
        } catch (ProviderNotConfiguredException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new McpClientBoundaryException("MCP 只读工具调用失败。", error);
        }
    }

    private McpSyncClient requiredClient() {
        return configuredClients().stream().findFirst()
                .orElseThrow(() -> new ProviderNotConfiguredException("MCP Client 未配置。"));
    }

    private List<McpSyncClient> configuredClients() {
        List<McpSyncClient> configured = clients.getIfAvailable();
        return configured == null ? List.of() : List.copyOf(configured);
    }

    public record McpClientStatus(boolean configured, int clientCount, String type, String policy) {
    }

    public record McpRemoteTool(
            String name,
            String title,
            String description,
            Object inputSchema,
            boolean readOnly
    ) {
    }

    public record McpRemoteCallResult(
            String toolName,
            boolean success,
            Boolean error,
            Object content,
            Object structuredContent
    ) {
    }
}
