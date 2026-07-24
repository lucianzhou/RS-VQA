package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

class McpClientBoundaryServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void reportsDisabledClientWithoutInventingRemoteCapabilities() {
        ObjectProvider<List<McpSyncClient>> provider = Mockito.mock(ObjectProvider.class);
        McpClientBoundaryService service = new McpClientBoundaryService(provider);

        assertThat(service.status().configured()).isFalse();
        assertThat(service.status().clientCount()).isZero();
        assertThatThrownBy(service::discoverTools)
                .isInstanceOf(ProviderNotConfiguredException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void discoveryFiltersToolsThroughTheReadOnlyAllowlist() {
        ObjectProvider<List<McpSyncClient>> provider = Mockito.mock(ObjectProvider.class);
        McpSyncClient client = Mockito.mock(McpSyncClient.class);
        when(provider.getIfAvailable()).thenReturn(List.of(client));
        JsonSchema schema = new JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of());
        Tool safe = new Tool(
                "current_model_release", null, "read-only", schema, Map.of(), null, Map.of()
        );
        Tool unsafe = new Tool(
                "delete_everything", null, "unsafe", schema, Map.of(), null, Map.of()
        );
        when(client.listTools()).thenReturn(new ListToolsResult(List.of(safe, unsafe), null));

        McpClientBoundaryService service = new McpClientBoundaryService(provider);

        assertThat(service.status().configured()).isTrue();
        assertThat(service.discoverTools())
                .extracting(McpClientBoundaryService.McpRemoteTool::name)
                .containsExactly("current_model_release");
        assertThatThrownBy(() -> service.call("delete_everything", Map.of()))
                .isInstanceOf(RequestValidationException.class);
    }
}
