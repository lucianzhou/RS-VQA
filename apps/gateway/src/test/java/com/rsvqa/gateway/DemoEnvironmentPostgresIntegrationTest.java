package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.rsvqa.gateway.domain.AgentActionProposalEntity;
import com.rsvqa.gateway.domain.AgentRunEntity;
import com.rsvqa.gateway.domain.AgentSessionEntity;
import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.domain.BatchItemEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ImageAssetEntity;
import com.rsvqa.gateway.domain.KnowledgeDocumentEntity;
import com.rsvqa.gateway.domain.MessageEntity;
import com.rsvqa.gateway.domain.ModelInvocationEntity;
import com.rsvqa.gateway.domain.ModelReleaseEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.ReportEntity;
import com.rsvqa.gateway.domain.ReportVersionEntity;
import com.rsvqa.gateway.domain.ToolInvocationEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentActionProposalRepository;
import com.rsvqa.gateway.repository.AgentRunRepository;
import com.rsvqa.gateway.repository.AgentSessionRepository;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ImageAssetRepository;
import com.rsvqa.gateway.repository.KnowledgeDocumentRepository;
import com.rsvqa.gateway.repository.MessageRepository;
import com.rsvqa.gateway.repository.ModelInvocationRepository;
import com.rsvqa.gateway.repository.ModelReleaseRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.ReportRepository;
import com.rsvqa.gateway.repository.ReportVersionRepository;
import com.rsvqa.gateway.repository.ToolInvocationRepository;
import com.rsvqa.gateway.repository.UserRepository;

@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15439/rsvqa_demo_test",
        "spring.datasource.username=rsvqa",
        "spring.datasource.password=rsvqa_test_only",
        "rsvqa.demo-auth.enabled=true",
        "rsvqa.demo-environment.enabled=true",
        "rsvqa.demo-environment.source-root=${user.dir}/../../data/defense-benchmark-v1",
        "rsvqa.demo-environment.model-release-id=rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2",
        "rsvqa.storage.root=/tmp/rsvqa-demo-environment-integration",
        "rsvqa.batch.recovery-enabled=false",
        "rsvqa.mcp.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RSVQA_DEMO_INTEGRATION", matches = "true")
class DemoEnvironmentPostgresIntegrationTest {

    @Autowired DemoEnvironmentService environment;
    @Autowired DemoShowcaseCatalog catalog;
    @Autowired FileStorageService storage;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ConversationRepository conversations;
    @Autowired ImageAssetRepository images;
    @Autowired ModelInvocationRepository invocations;
    @Autowired MessageRepository messages;
    @Autowired BatchJobRepository batchJobs;
    @Autowired BatchItemRepository batchItems;
    @Autowired AgentSessionRepository agentSessions;
    @Autowired AgentRunRepository agentRuns;
    @Autowired ToolInvocationRepository toolInvocations;
    @Autowired AgentActionProposalRepository proposals;
    @Autowired ReportRepository reports;
    @Autowired ReportVersionRepository reportVersions;
    @Autowired AuditEventRepository auditEvents;
    @Autowired KnowledgeDocumentRepository knowledgeDocuments;
    @Autowired ModelReleaseRepository modelReleases;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @MockitoBean DemoRuntimeInitializer runtimeInitializer;
    @MockitoBean VqaService vqa;
    @MockitoBean StringRedisTemplate redis;

    Path sourceRoot;

    @BeforeEach
    void prepare() throws Exception {
        sourceRoot = Path.of("../../data/defense-benchmark-v1").toRealPath();
        Path uploadRoot = Path.of("/tmp/rsvqa-demo-environment-integration");
        if (Files.exists(uploadRoot)) {
            try (var paths = Files.walk(uploadRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
        }
        jdbc.update("DELETE FROM knowledge_chunk", Map.of());
        jdbc.update("DELETE FROM knowledge_document", Map.of());
        jdbc.update("DELETE FROM tool_invocation", Map.of());
        jdbc.update("DELETE FROM agent_action_proposal", Map.of());
        jdbc.update("DELETE FROM agent_run", Map.of());
        jdbc.update("DELETE FROM agent_session", Map.of());
        jdbc.update("DELETE FROM report_version", Map.of());
        jdbc.update("DELETE FROM report", Map.of());
        jdbc.update("DELETE FROM batch_item", Map.of());
        jdbc.update("DELETE FROM batch_job", Map.of());
        jdbc.update("DELETE FROM message", Map.of());
        jdbc.update("DELETE FROM model_invocation", Map.of());
        jdbc.update("DELETE FROM image_asset", Map.of());
        jdbc.update("DELETE FROM conversation", Map.of());
        jdbc.update("DELETE FROM project", Map.of());
        jdbc.update("DELETE FROM audit_event", Map.of());
        jdbc.update("DELETE FROM user_setting", Map.of());
        jdbc.update("DELETE FROM app_user", Map.of());
        jdbc.update("DELETE FROM model_release", Map.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resetIsIdempotentAndProtectsUsersKnowledgeReleasesAndFrozenSource() throws Exception {
        UserEntity demo = users.save(new UserEntity(
                DemoEnvironmentService.DEMO_USERNAME, "Local Demo", "USER", true
        ));
        UserEntity real = users.save(new UserEntity(
                "real-user", "Real User", "USER", false
        ));
        ProjectEntity realProject = projects.save(new ProjectEntity(real, "真实用户项目"));
        projects.save(new ProjectEntity(demo, "旧演示项目"));

        KnowledgeDocumentEntity privateDemoKnowledge = knowledgeDocuments.save(new KnowledgeDocumentEntity(
                demo, "演示用户私有知识", "a".repeat(64), "text/markdown", "test-v2", "PRIVATE"
        ));
        KnowledgeDocumentEntity publicKnowledge = knowledgeDocuments.save(new KnowledgeDocumentEntity(
                real, "公共知识", "b".repeat(64), "text/markdown", "test-v2", "PUBLIC"
        ));
        ModelReleaseEntity release = modelReleases.save(new ModelReleaseEntity(
                "protected-release", "RESEARCH_MODEL", "REAL", "{}", true
        ));
        OldDemoGraph old = createOldDemoGraph(demo);

        String questionsDigestBefore = sha256(sourceRoot.resolve(DemoShowcaseCatalog.QUESTIONS_FILE));
        String imageDigestBefore = sha256(sourceRoot.resolve("images/0001_3207.png"));
        given(vqa.currentModel()).willReturn(new RuntimeModelInfoResponse(
                "real",
                true,
                "rsvqa-hr-qdrop15-predicted-soft-20260727-9b4ade2",
                "1.0",
                "rsvqa_hr_grouped_answer_classification",
                "predicted_soft",
                "research_vilt_predicted_soft",
                List.of("count limitation"),
                Map.of()
        ));
        authenticateDemo();

        DemoEnvironmentDtos.ResetResponse first = environment.reset(
                DemoEnvironmentService.CONFIRMATION
        );

        assertThat(first.showcaseItems()).isEqualTo(24);
        assertThat(first.conversationIds()).hasSize(3);
        assertThat(first.agentSessionIds()).hasSize(2);
        assertThat(projects.findById(realProject.getId())).isPresent();
        assertThat(projects.findById(old.projectId())).isEmpty();
        assertThat(modelReleases.findById(release.getId())).isPresent();
        assertThat(knowledgeDocuments.findById(privateDemoKnowledge.getId())).isPresent();
        assertThat(knowledgeDocuments.findById(publicKnowledge.getId())).isPresent();
        assertThat(users.findById(real.getId())).isPresent();
        assertThat(users.findById(demo.getId())).isPresent();
        assertThat(batchItems.findByBatchJobIdOrderByCreatedAtAsc(first.batchJobId()))
                .hasSize(24)
                .allSatisfy(item -> {
                    assertThat(item.getStorageKey()).startsWith(demo.getId() + "/batch/");
                    assertThat(item.getStatus()).isEqualTo("QUEUED");
                });
        assertThat(conversations.findByProjectIdOrderByUpdatedAtDesc(first.projectId())).hasSize(3);
        assertThat(agentSessions.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(demo.getId())).hasSize(2);
        assertThat(Files.exists(Path.of("/tmp/rsvqa-demo-environment-integration").resolve(old.storageKey())))
                .isFalse();
        assertThat(sha256(sourceRoot.resolve(DemoShowcaseCatalog.QUESTIONS_FILE)))
                .isEqualTo(questionsDigestBefore);
        assertThat(sha256(sourceRoot.resolve("images/0001_3207.png")))
                .isEqualTo(imageDigestBefore);

        DemoEnvironmentDtos.ResetResponse second = environment.reset(
                DemoEnvironmentService.CONFIRMATION
        );

        assertThat(second.projectId()).isNotEqualTo(first.projectId());
        assertThat(projects.findById(first.projectId())).isEmpty();
        assertThat(projects.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(demo.getId())).hasSize(1);
        assertThat(conversations.findByProjectIdOrderByUpdatedAtDesc(second.projectId())).hasSize(3);
        assertThat(batchJobs.findByUserIdAndArchivedFalseOrderByCreatedAtDesc(demo.getId())).hasSize(1);
        assertThat(batchItems.findByBatchJobIdOrderByCreatedAtAsc(second.batchJobId())).hasSize(24);
        assertThat(agentSessions.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(demo.getId())).hasSize(2);
        assertThat(modelReleases.findById(release.getId())).isPresent();
        assertThat(knowledgeDocuments.count()).isEqualTo(2);
        assertThat(projects.findById(realProject.getId())).isPresent();
        assertThat(auditEvents.findTop100ByUserIdOrderByCreatedAtDesc(demo.getId()))
                .extracting(AuditEventEntity::getEventType)
                .containsExactly("DEMO_ENVIRONMENT_RESET");
    }

    private OldDemoGraph createOldDemoGraph(UserEntity demo) {
        ProjectEntity project = projects.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(demo.getId()).getFirst();
        ConversationEntity conversation = conversations.save(new ConversationEntity(project, "旧会话"));
        DemoShowcaseCatalog.ShowcaseCase sample = catalog.load(sourceRoot).getFirst();
        FileStorageService.StoredImage stored = storage.storeDemoConversation(
                demo.getId(), conversation.getId(), sample.imagePath(), sample.originalName()
        );
        ImageAssetEntity image = images.save(new ImageAssetEntity(
                conversation, stored.storageKey(), stored.originalName(), stored.sha256(), stored.mimeType(),
                stored.sizeBytes(), stored.width(), stored.height()
        ));
        ModelInvocationEntity invocation = invocations.save(new ModelInvocationEntity(
                conversation, "old-release", "RESEARCH_MODEL", "research", sample.question(), "no",
                "answered", 0.5, 0.1, sample.questionType(), "[]", "{}", 1L, "old-request"
        ));
        messages.save(new MessageEntity(conversation, invocation, "assistant", "RESEARCH_MODEL", "no", "{}"));

        BatchJobEntity batch = batchJobs.save(new BatchJobEntity(demo, project, "old-release", 1));
        FileStorageService.StoredImage batchImage = storage.storeDemoBatch(
                demo.getId(), batch.getId(), sample.imagePath(), sample.originalName()
        );
        batchItems.save(new BatchItemEntity(batch, descriptor(batchImage), sample.question()));

        AgentSessionEntity session = agentSessions.save(new AgentSessionEntity(
                demo, project, null, null, "旧 Agent 会话"
        ));
        AgentRunEntity run = agentRuns.save(new AgentRunEntity(
                demo, session, project, conversation, null, "旧问题", "old-trace"
        ));
        run.complete("旧结果", 1);
        agentRuns.save(run);
        ToolInvocationEntity tool = toolInvocations.save(new ToolInvocationEntity(run, "project_summary", "{}"));
        tool.complete("{}", 1);
        toolInvocations.save(tool);
        proposals.save(new AgentActionProposalEntity(
                demo, session, "archive_project", "{}", "旧提案", "old-proposal", Instant.now().plusSeconds(60)
        ));

        ReportEntity report = reports.save(new ReportEntity(
                demo, project, null, "旧报告", "PROJECT_ANALYSIS", "old-report"
        ));
        reportVersions.save(new ReportVersionEntity(
                report, 1, "{}", "# 旧报告", null, "[]", "old-release",
                "deterministic_backend_statistics", "TEST"
        ));
        auditEvents.save(new AuditEventEntity(
                demo, "OLD_EVENT", "PROJECT", project.getId(), "old-trace", "SUCCESS", "old"
        ));
        return new OldDemoGraph(project.getId(), image.getStorageKey());
    }

    private static BatchItemEntity.FileDescriptor descriptor(FileStorageService.StoredImage image) {
        return new BatchItemEntity.FileDescriptor(
                image.storageKey(), image.originalName(), image.sha256(), image.mimeType(),
                image.sizeBytes(), image.width(), image.height()
        );
    }

    private static void authenticateDemo() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        DemoEnvironmentService.DEMO_USERNAME,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
    }

    private static String sha256(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    private record OldDemoGraph(UUID projectId, String storageKey) {
    }
}
