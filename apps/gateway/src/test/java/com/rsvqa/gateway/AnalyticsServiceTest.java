package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ModelInvocationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ImageAssetRepository;
import com.rsvqa.gateway.repository.ModelInvocationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

class AnalyticsServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ImageAssetRepository images = mock(ImageAssetRepository.class);
    private final ModelInvocationRepository invocations = mock(ModelInvocationRepository.class);
    private final BatchJobRepository batchJobs = mock(BatchJobRepository.class);
    private final BatchItemRepository batchItems = mock(BatchItemRepository.class);
    private final AnalyticsService service = new AnalyticsService(
            users, projects, conversations, images, invocations, batchJobs, batchItems
    );

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("demo", "n/a", List.of())
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void computesDistributionsAndReviewCasesFromPersistedFacts() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserEntity user = mock(UserEntity.class);
        ProjectEntity project = mock(ProjectEntity.class);
        ConversationEntity conversation = mock(ConversationEntity.class);
        when(user.getId()).thenReturn(userId);
        when(project.getName()).thenReturn("城市土地利用");
        when(conversation.getId()).thenReturn(conversationId);
        when(conversation.getTitle()).thenReturn("phoenix");
        when(users.findByUsername("demo")).thenReturn(Optional.of(user));
        when(projects.findByIdAndUserId(projectId, userId)).thenReturn(Optional.of(project));
        when(conversations.findByProjectIdOrderByUpdatedAtDesc(projectId)).thenReturn(List.of(conversation));
        when(images.findByConversationId(conversationId)).thenReturn(Optional.empty());

        ModelInvocationEntity strong = invocation(
                conversation, "图中有没有道路？", "yes", "answered", "presence", 0.90, 0.42, "request-1"
        );
        ModelInvocationEntity weak = invocation(
                conversation, "图中有多少建筑物？", "4", "answered", "count", 0.50, 0.08, "request-2"
        );
        ModelInvocationEntity rejected = invocation(
                conversation, "请判断火灾风险", null, "unsupported", null, null, null, "request-3"
        );
        when(invocations.findByConversationProjectIdOrderByCreatedAtAsc(projectId))
                .thenReturn(List.of(strong, weak, rejected));

        var result = service.project(projectId);

        assertThat(result.questionCount()).isEqualTo(3);
        assertThat(result.answeredCount()).isEqualTo(2);
        assertThat(result.unsupportedCount()).isEqualTo(1);
        assertThat(result.lowConfidenceCount()).isEqualTo(1);
        assertThat(result.averageConfidence()).isEqualTo(0.7);
        assertThat(result.questionTypeDistribution())
                .containsEntry("presence", 1L)
                .containsEntry("count", 1L)
                .containsEntry("other", 1L);
        assertThat(result.answerDistribution()).containsEntry("yes", 1L).containsEntry("4", 1L);
        assertThat(result.reviewCases()).extracting(AnalyticsDtos.AnalysisCase::requestId)
                .containsExactly("request-2", "request-3");
        assertThat(result.calculationBoundary()).contains("确定性计算");
    }

    @Test
    void fallsBackToDeterministicQuestionTypeClassification() {
        assertThat(AnalyticsService.resolvedQuestionType(null, "How many buildings are visible?")).isEqualTo("count");
        assertThat(AnalyticsService.resolvedQuestionType(null, "建筑物覆盖面积是多少？")).isEqualTo("area");
        assertThat(AnalyticsService.resolvedQuestionType(null, "图中是否存在道路？")).isEqualTo("presence");
        assertThat(AnalyticsService.resolvedQuestionType(null, "请描述这张图片")).isEqualTo("other");
    }

    private static ModelInvocationEntity invocation(
            ConversationEntity conversation,
            String question,
            String answer,
            String status,
            String questionType,
            Double confidence,
            Double margin,
            String requestId
    ) {
        return new ModelInvocationEntity(
                conversation,
                "release-1",
                "RESEARCH_MODEL",
                "research_vilt_predicted_soft",
                question,
                answer,
                status,
                confidence,
                margin,
                questionType,
                "[]",
                "{}",
                10L,
                requestId
        );
    }
}
