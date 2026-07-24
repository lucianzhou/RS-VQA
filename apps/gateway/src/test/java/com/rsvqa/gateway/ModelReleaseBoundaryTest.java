package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
class ModelReleaseBoundaryTest {

    @Test
    void supportedQuestionToolStatesClosedSetBoundary() {
        TrustedAgentTools tools = new TrustedAgentTools(
                Mockito.mock(VqaService.class),
                Mockito.mock(WorkspaceService.class),
                Mockito.mock(BatchService.class),
                Mockito.mock(KnowledgeService.class),
                Mockito.mock(AnalyticsService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        Map<String, Object> boundary = tools.supportedQuestionTypes();

        assertThat(boundary.get("deploymentProtocol")).isEqualTo("qdrop15 + predicted-soft");
        assertThat((List<?>) boundary.get("supported")).hasSize(4);
        assertThat(((List<?>) boundary.get("unsupported")).stream().map(Object::toString))
                .contains("开放式图像描述", "零样本识别");
    }

    @Test
    void springAiPublishesOnlyDeclaredReadOnlyToolCallbacks() {
        TrustedAgentTools tools = new TrustedAgentTools(
                Mockito.mock(VqaService.class),
                Mockito.mock(WorkspaceService.class),
                Mockito.mock(BatchService.class),
                Mockito.mock(KnowledgeService.class),
                Mockito.mock(AnalyticsService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        var provider = new SpringAiToolConfiguration().rsVqaReadOnlyTools(tools);
        List<String> names = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "current_model_release",
                "supported_question_types",
                "system_health",
                "conversation_history",
                "batch_job_status",
                "project_vqa_statistics",
                "batch_result_statistics",
                "report_draft_data",
                "search_knowledge"
        );
    }

    @Test
    void formalAgentCatalogAddsControlledVqaWithoutChangingReadOnlyMcpProvider() {
        TrustedAgentTools readOnlyTools = new TrustedAgentTools(
                Mockito.mock(VqaService.class),
                Mockito.mock(WorkspaceService.class),
                Mockito.mock(BatchService.class),
                Mockito.mock(KnowledgeService.class),
                Mockito.mock(AnalyticsService.class),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        AgentToolRegistry registry = new AgentToolRegistry(
                readOnlyTools,
                Mockito.mock(WorkspaceService.class)
        );

        List<String> names = Arrays.stream(registry.callbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(names).contains("single_image_vqa");
        assertThat(names).hasSize(10);
    }
}
