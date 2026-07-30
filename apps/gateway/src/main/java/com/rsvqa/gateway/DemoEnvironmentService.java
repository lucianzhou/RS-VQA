package com.rsvqa.gateway;

import static com.rsvqa.gateway.DemoEnvironmentDtos.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.rsvqa.gateway.DemoShowcaseCatalog.ShowcaseCase;
import com.rsvqa.gateway.domain.AgentSessionEntity;
import com.rsvqa.gateway.domain.AuditEventEntity;
import com.rsvqa.gateway.domain.BatchItemEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ImageAssetEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.AgentSessionRepository;
import com.rsvqa.gateway.repository.AuditEventRepository;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ImageAssetRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
public class DemoEnvironmentService {

    static final String DEMO_USERNAME = "local-demo";
    static final String CONFIRMATION = "RESET_LOCAL_DEMO";

    private final DemoEnvironmentProperties properties;
    private final DemoShowcaseCatalog catalog;
    private final DemoEnvironmentStore store;
    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final ImageAssetRepository images;
    private final BatchJobRepository batchJobs;
    private final BatchItemRepository batchItems;
    private final AgentSessionRepository agentSessions;
    private final AuditEventRepository auditEvents;
    private final FileStorageService storage;
    private final VqaService vqa;
    private final ApplicationEventPublisher events;

    public DemoEnvironmentService(
            DemoEnvironmentProperties properties,
            DemoShowcaseCatalog catalog,
            DemoEnvironmentStore store,
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            ImageAssetRepository images,
            BatchJobRepository batchJobs,
            BatchItemRepository batchItems,
            AgentSessionRepository agentSessions,
            AuditEventRepository auditEvents,
            FileStorageService storage,
            VqaService vqa,
            ApplicationEventPublisher events
    ) {
        this.properties = properties;
        this.catalog = catalog;
        this.store = store;
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.images = images;
        this.batchJobs = batchJobs;
        this.batchItems = batchItems;
        this.agentSessions = agentSessions;
        this.auditEvents = auditEvents;
        this.storage = storage;
        this.vqa = vqa;
        this.events = events;
    }

    @Transactional
    public ResetResponse reset(String confirmation) {
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UserEntity user = currentDemoUser();
        if (!CONFIRMATION.equals(confirmation)) {
            throw new RequestValidationException(
                    "确认短语不匹配；请输入 " + CONFIRMATION + "。"
            );
        }
        if (properties.sourceRoot() == null || properties.sourceRoot().isBlank()) {
            throw new RequestValidationException("答辩演示数据目录未配置。");
        }
        if (properties.modelReleaseId() == null || properties.modelReleaseId().isBlank()) {
            throw new RequestValidationException("答辩演示研究模型发布未配置。");
        }
        RuntimeModelInfoResponse runtime = verifiedRuntime();

        List<ShowcaseCase> showcase = catalog.load(Path.of(properties.sourceRoot()));
        List<String> obsoleteStorageKeys = store.storageKeys(user.getId());
        DemoEnvironmentStore.ClearCounts cleared = store.clearUserData(user.getId());
        List<String> createdStorageKeys = new ArrayList<>();
        registerRollbackCleanup(user.getId(), createdStorageKeys);

        ProjectEntity project = projects.save(new ProjectEntity(
                user,
                "答辩演示 · 受控遥感问答"
        ));
        ShowcaseCase presence = firstOfType(showcase, "presence");
        ShowcaseCase count = lastOfType(showcase, "count");

        ConversationEntity single = createConversationWithImage(
                user, project, "单图问答 · Presence", presence, createdStorageKeys
        );
        ConversationEntity multi = createConversationWithImage(
                user, project, "同图多轮 · 规范问法与自然问法", presence, createdStorageKeys
        );
        ConversationEntity countReview = createConversationWithImage(
                user, project, "Count 输出 · 需人工复核", count, createdStorageKeys
        );

        BatchJobEntity batch = batchJobs.save(new BatchJobEntity(
                user,
                project,
                runtime.modelReleaseId(),
                showcase.size()
        ));
        for (ShowcaseCase item : showcase) {
            FileStorageService.StoredImage stored = storage.storeDemoBatch(
                    user.getId(),
                    batch.getId(),
                    item.imagePath(),
                    item.originalName()
            );
            createdStorageKeys.add(stored.storageKey());
            batchItems.save(new BatchItemEntity(
                    batch,
                    descriptor(stored),
                    item.question()
            ));
        }

        AgentSessionEntity projectAgent = agentSessions.save(new AgentSessionEntity(
                user,
                project,
                null,
                null,
                "RS-Bot · 答辩项目分析"
        ));
        AgentSessionEntity batchAgent = agentSessions.save(new AgentSessionEntity(
                user,
                null,
                null,
                batch,
                "RS-Bot · 24 项批量复核"
        ));

        auditEvents.save(new AuditEventEntity(
                user,
                "DEMO_ENVIRONMENT_RESET",
                "DEMO_ENVIRONMENT",
                project.getId(),
                TraceId.current(),
                "SUCCESS",
                "cleared_rows=" + cleared.totalRows() + ",showcase_items=" + showcase.size()
        ));

        List<DemoSeedReadyEvent.ConversationSeed> runtimeSeeds = List.of(
                new DemoSeedReadyEvent.ConversationSeed(
                        single.getId(),
                        List.of(presence.question())
                ),
                new DemoSeedReadyEvent.ConversationSeed(
                        multi.getId(),
                        List.of(
                                presence.question(),
                                "Is there a " + objectPhrase(presence.primaryObject()) + " in the image?"
                        )
                ),
                new DemoSeedReadyEvent.ConversationSeed(
                        countReview.getId(),
                        List.of(count.question())
                )
        );
        events.publishEvent(new DemoSeedReadyEvent(
                user.getId(),
                obsoleteStorageKeys,
                batch.getId(),
                runtime.modelReleaseId(),
                runtimeSeeds
        ));

        return new ResetResponse(
                "INITIALIZING_RUNTIME_OUTPUTS",
                project.getId(),
                List.of(single.getId(), multi.getId(), countReview.getId()),
                batch.getId(),
                List.of(projectAgent.getId(), batchAgent.getId()),
                showcase.size(),
                List.of(
                        "单图、多轮和批量结果正在通过现有研究模型运行时生成。",
                        "当前运行时已核对为 real + predicted_soft + " + runtime.modelReleaseId() + "。",
                        "24 项展示集固定包含四类问题各 6 条，不用于替代正式总体指标。",
                        "Count 非零和密集目标存在系统性低估风险，展示结果必须保留人工复核提示。",
                        "RS-Bot 仅预建受控上下文，不伪造 Gemini 回答。"
                )
        );
    }

    private ConversationEntity createConversationWithImage(
            UserEntity user,
            ProjectEntity project,
            String title,
            ShowcaseCase item,
            List<String> createdStorageKeys
    ) {
        ConversationEntity conversation = conversations.save(new ConversationEntity(project, title));
        FileStorageService.StoredImage stored = storage.storeDemoConversation(
                user.getId(),
                conversation.getId(),
                item.imagePath(),
                item.originalName()
        );
        createdStorageKeys.add(stored.storageKey());
        images.save(new ImageAssetEntity(
                conversation,
                stored.storageKey(),
                stored.originalName(),
                stored.sha256(),
                stored.mimeType(),
                stored.sizeBytes(),
                stored.width(),
                stored.height()
        ));
        return conversation;
    }

    private void registerRollbackCleanup(UUID userId, List<String> createdStorageKeys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    createdStorageKeys.forEach(key -> storage.deleteOwned(userId, key));
                }
            }
        });
    }

    private UserEntity currentDemoUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !DEMO_USERNAME.equals(authentication.getName())) {
            throw new AccessDeniedException("仅本地演示用户可以重置答辩环境。");
        }
        UserEntity user = users.findByUsername(DEMO_USERNAME)
                .orElseThrow(() -> new ResourceNotFoundException("本地演示用户不存在。"));
        if (!user.isDemo()) {
            throw new AccessDeniedException("当前账户不是受控演示账户。");
        }
        return user;
    }

    private RuntimeModelInfoResponse verifiedRuntime() {
        RuntimeModelInfoResponse runtime = vqa.currentModel();
        if (!runtime.ready()
                || !"real".equalsIgnoreCase(runtime.mode())
                || !"predicted_soft".equalsIgnoreCase(runtime.typeSourceMode())
                || !properties.modelReleaseId().equals(runtime.modelReleaseId())
                || runtime.predictionOrigin() == null
                || runtime.predictionOrigin().toLowerCase(Locale.ROOT).contains("mock")) {
            throw new RequestValidationException(
                    "答辩环境只允许使用已核准的真实 predicted-soft 模型发布；"
                            + "当前运行时未就绪、处于 Mock 或发布身份不匹配，未执行重置。"
            );
        }
        return runtime;
    }

    private static ShowcaseCase firstOfType(List<ShowcaseCase> cases, String type) {
        return cases.stream().filter(item -> type.equals(item.questionType())).findFirst()
                .orElseThrow(() -> new RequestValidationException("答辩演示题目类型不完整。"));
    }

    private static ShowcaseCase lastOfType(List<ShowcaseCase> cases, String type) {
        ShowcaseCase selected = null;
        for (ShowcaseCase item : cases) {
            if (type.equals(item.questionType())) {
                selected = item;
            }
        }
        if (selected == null) {
            throw new RequestValidationException("答辩演示题目类型不完整。");
        }
        return selected;
    }

    private static BatchItemEntity.FileDescriptor descriptor(FileStorageService.StoredImage image) {
        return new BatchItemEntity.FileDescriptor(
                image.storageKey(),
                image.originalName(),
                image.sha256(),
                image.mimeType(),
                image.sizeBytes(),
                image.width(),
                image.height()
        );
    }

    private static String objectPhrase(String object) {
        String phrase = object.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return phrase.isBlank() ? "supported object" : phrase;
    }
}
