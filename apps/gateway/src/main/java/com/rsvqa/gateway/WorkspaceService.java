package com.rsvqa.gateway;

import static com.rsvqa.gateway.WorkspaceDtos.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsvqa.gateway.domain.ConversationEntity;
import com.rsvqa.gateway.domain.ImageAssetEntity;
import com.rsvqa.gateway.domain.MessageEntity;
import com.rsvqa.gateway.domain.ModelInvocationEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.ConversationRepository;
import com.rsvqa.gateway.repository.ImageAssetRepository;
import com.rsvqa.gateway.repository.MessageRepository;
import com.rsvqa.gateway.repository.ModelInvocationRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;
import com.rsvqa.gateway.repository.UserSettingRepository;

@Service
public class WorkspaceService {

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final ImageAssetRepository images;
    private final MessageRepository messages;
    private final ModelInvocationRepository invocations;
    private final FileStorageService storage;
    private final VqaService vqaService;
    private final List<AiProvider> aiProviders;
    private final UserSettingRepository userSettings;
    private final ObjectMapper objectMapper;

    public WorkspaceService(
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            ImageAssetRepository images,
            MessageRepository messages,
            ModelInvocationRepository invocations,
            FileStorageService storage,
            VqaService vqaService,
            List<AiProvider> aiProviders,
            UserSettingRepository userSettings,
            ObjectMapper objectMapper
    ) {
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.images = images;
        this.messages = messages;
        this.invocations = invocations;
        this.storage = storage;
        this.vqaService = vqaService;
        this.aiProviders = aiProviders;
        this.userSettings = userSettings;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects() {
        UserEntity user = currentUser();
        return projects.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(user.getId()).stream()
                .map(project -> toProject(project, user.getId()))
                .toList();
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        UserEntity user = currentUser();
        ProjectEntity project = projects.save(new ProjectEntity(user, request.name().trim()));
        return toProject(project, user.getId());
    }

    @Transactional
    public ProjectResponse updateProject(UUID projectId, UpdateProjectRequest request) {
        UserEntity user = currentUser();
        ProjectEntity project = ownedProject(projectId, user.getId());
        project.rename(request.name().trim());
        return toProject(project, user.getId());
    }

    @Transactional
    public void archiveProject(UUID projectId) {
        UserEntity user = currentUser();
        ownedProject(projectId, user.getId()).archive();
    }

    @Transactional
    public void restoreProject(UUID projectId) {
        UserEntity user = currentUser();
        ownedProject(projectId, user.getId()).restore();
    }

    @Transactional
    public ConversationResponse createConversation(UUID projectId, CreateConversationRequest request) {
        UserEntity user = currentUser();
        ProjectEntity project = projects.findByIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
        String title = request.title() == null || request.title().isBlank() ? "新分析" : request.title().trim();
        ConversationEntity conversation = conversations.save(new ConversationEntity(project, title));
        return toConversation(conversation);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(UUID conversationId) {
        return toConversation(ownedConversation(conversationId));
    }

    @Transactional
    public ConversationResponse updateConversation(UUID conversationId, UpdateConversationRequest request) {
        UserEntity user = currentUser();
        ConversationEntity conversation = ownedConversation(conversationId);
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new RequestValidationException("会话标题不能为空。");
            }
            conversation.rename(request.title().trim());
        }
        if (request.projectId() != null && !request.projectId().equals(conversation.getProject().getId())) {
            ProjectEntity destination = ownedProject(request.projectId(), user.getId());
            if (destination.isArchived()) {
                throw new RequestValidationException("不能将会话移动到已归档项目。");
            }
            conversation.moveTo(destination);
        }
        return toConversation(conversation);
    }

    @Transactional
    public void archiveConversation(UUID conversationId) {
        ownedConversation(conversationId).archive();
    }

    @Transactional
    public void restoreConversation(UUID conversationId) {
        ConversationEntity conversation = ownedConversation(conversationId);
        if (conversation.getProject().isArchived()) {
            throw new RequestValidationException("请先恢复会话所属项目。");
        }
        conversation.restore();
    }

    @Transactional(readOnly = true)
    public ArchiveResponse archive() {
        UserEntity user = currentUser();
        List<ArchivedProjectResponse> archivedProjects = projects
                .findByUserIdAndArchivedTrueOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(project -> new ArchivedProjectResponse(project.getId(), project.getName(), project.getUpdatedAt()))
                .toList();
        List<ArchivedConversationResponse> archivedConversations = conversations
                .findByProjectUserIdAndArchivedTrueOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(conversation -> new ArchivedConversationResponse(
                        conversation.getId(),
                        conversation.getProject().getId(),
                        conversation.getProject().getName(),
                        conversation.getTitle(),
                        conversation.getUpdatedAt()
                ))
                .toList();
        return new ArchiveResponse(archivedProjects, archivedConversations);
    }

    @Transactional
    public ImageResponse uploadImage(UUID conversationId, MultipartFile upload) {
        UserEntity user = currentUser();
        ConversationEntity conversation = conversations.findByIdAndProjectUserId(conversationId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在。"));
        FileStorageService.StoredImage stored = storage.store(user.getId(), conversationId, upload);
        ImageAssetEntity previous = images.findByConversationId(conversationId).orElse(null);
        if (previous != null) {
            images.delete(previous);
            images.flush();
        }
        ImageAssetEntity asset = images.save(new ImageAssetEntity(
                conversation,
                stored.storageKey(),
                stored.originalName(),
                stored.sha256(),
                stored.mimeType(),
                stored.sizeBytes(),
                stored.width(),
                stored.height()
        ));
        conversation.rename(removeExtension(stored.originalName()));
        messages.deleteAll(messages.findByConversationIdOrderByCreatedAtAsc(conversationId));
        if (previous != null) {
            storage.delete(previous.getStorageKey());
        }
        return toImage(asset);
    }

    @Transactional
    public void deleteImage(UUID conversationId) {
        ownedConversation(conversationId);
        images.findByConversationId(conversationId).ifPresent(asset -> {
            images.delete(asset);
            messages.deleteAll(messages.findByConversationIdOrderByCreatedAtAsc(conversationId));
            storage.delete(asset.getStorageKey());
        });
    }

    @Transactional(readOnly = true)
    public ImageContent imageContent(UUID conversationId) {
        ownedConversation(conversationId);
        ImageAssetEntity image = images.findByConversationId(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("会话尚未上传图像。"));
        return new ImageContent(storage.read(image.getStorageKey()), image.getMimeType(), image.getOriginalName());
    }

    @Transactional(readOnly = true)
    public ReportContent report(UUID conversationId) {
        ConversationResponse conversation = toConversation(ownedConversation(conversationId));
        StringBuilder markdown = new StringBuilder()
                .append("# RS-VQA 分析记录\n\n")
                .append("- 会话：").append(conversation.title()).append("\n")
                .append("- 生成时间：").append(java.time.Instant.now()).append("\n")
                .append("- 运行边界：RSVQA-HR grouped-answer 闭集分类；不是开放式视觉问答。\n");
        if (conversation.image() != null) {
            markdown.append("- 影像：").append(conversation.image().originalName())
                    .append("（SHA-256: `").append(conversation.image().sha256()).append("`）\n");
        }
        markdown.append("\n## 问答记录\n");
        for (MessageResponse message : conversation.messages()) {
            markdown.append("\n### ").append("user".equals(message.role()) ? "用户问题" : "系统回答").append("\n\n")
                    .append(message.content()).append("\n");
            if (message.invocation() != null) {
                markdown.append("\n> 来源：").append(message.sourceType())
                        .append("；发布版本：").append(message.invocation().modelReleaseId())
                        .append("；置信度：").append(message.invocation().confidence())
                        .append("；请求：").append(message.invocation().requestId()).append("\n");
            }
        }
        markdown.append("\n---\n本报告保留生成时的模型来源与调用版本。Mock 输出不得作为论文实验结论。\n");
        return new ReportContent(markdown.toString().getBytes(StandardCharsets.UTF_8), safeReportName(conversation.title()) + ".md");
    }

    @Transactional
    public QuestionResponse ask(UUID conversationId, QuestionRequest request) {
        ConversationEntity conversation = ownedConversation(conversationId);
        if (request.providerId() != null && !request.providerId().isBlank()
                && !"research-rsvqa".equals(request.providerId())) {
            ImageAssetEntity image = images.findByConversationId(conversationId)
                    .orElseThrow(() -> new RequestValidationException("请先为当前会话上传图像。"));
            String question = request.question().trim();
            MessageEntity userMessage = messages.save(new MessageEntity(
                    conversation,
                    null,
                    "user",
                    "USER",
                    question,
                    null
            ));
            return askExternal(conversation, image, userMessage, question, request.providerId().trim());
        }
        return askResearch(conversation, request.question(), request.modelReleaseId());
    }

    @Transactional
    public QuestionResponse askResearchForUser(
            UUID userId,
            UUID conversationId,
            String question,
            String modelReleaseId
    ) {
        ConversationEntity conversation = conversations.findByIdAndProjectUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("演示会话不存在或已被重置。"));
        return askResearch(conversation, question, modelReleaseId);
    }

    private QuestionResponse askResearch(
            ConversationEntity conversation,
            String rawQuestion,
            String modelReleaseId
    ) {
        UUID conversationId = conversation.getId();
        ImageAssetEntity image = images.findByConversationId(conversationId)
                .orElseThrow(() -> new RequestValidationException("请先为当前会话上传图像。"));
        String question = rawQuestion.trim();
        MessageEntity userMessage = messages.save(new MessageEntity(
                conversation,
                null,
                "user",
                "USER",
                question,
                null
        ));

        ApiPredictionResponse result = vqaService.answer(
                storage.read(image.getStorageKey()), image.getOriginalName(), image.getMimeType(),
                question, modelReleaseId
        );
        verifyInputDigest(image.getSha256(), result.inputSha256());
        ModelInvocationEntity invocation = new ModelInvocationEntity(
                conversation,
                result.modelReleaseId(),
                "RESEARCH_MODEL",
                result.predictionOrigin(),
                question,
                result.answer(),
                result.status(),
                result.confidence(),
                result.margin(),
                result.predictedQuestionType(),
                json(result.topK()),
                json(result.questionTypeProbabilities()),
                result.latencyMs(),
                result.requestId()
        );
        invocation.recordResearchProvenance(
                result.checkpointSha256(),
                result.answerVocabularySha256(),
                result.runtimeArtifactSha256()
        );
        invocation.recordInferenceContract(
                result.inputSha256(),
                result.taskScope(),
                json(result.limitations()),
                result.capabilityNotice(),
                result.reviewStatus(),
                result.automaticRejectionEnabled(),
                result.confidenceDisplayEnabled(),
                result.manualReviewSignalEnabled()
        );
        ApiPredictionResponse.QuestionUnderstanding understanding = result.understanding();
        ApiPredictionResponse.AnswerPresentation presentation = result.presentation();
        invocation.recordQuestionNormalization(
                understanding.canonicalQuestion(),
                understanding.canonicalQuestionDisplay(),
                understanding.modelInputQuestion(),
                understanding.normalizerVersion(),
                understanding.matchedIntent(),
                json(understanding.matchedObjects()),
                understanding.scopeVerification(),
                understanding.reasonCode(),
                understanding.needsClarification(),
                json(understanding.clarificationOptions()),
                presentation.displayAnswer(),
                presentation.displayLocale(),
                presentation.answerShapeMismatch()
        );
        invocation = invocations.save(invocation);
        String sourceType = "mock_demo".equals(result.predictionOrigin()) ? "MOCK" : "RESEARCH_MODEL";
        // The raw closed-set prediction is what gets stored and shown. The
        // localized rendering travels alongside it in metadata and never
        // replaces it.
        String content = result.answer() == null ? result.capabilityNotice() : result.answer();
        boolean requiresReview = requiresManualReview(result);
        String capabilityNotice = requiresReview
                ? result.capabilityNotice() + " 当前答案形式与预测题型不一致，请人工复核；系统保留原始模型输出，未进行规则改写。"
                : result.capabilityNotice();
        Map<String, Object> metadataValues = new LinkedHashMap<>();
        metadataValues.put("capabilityNotice", capabilityNotice);
        metadataValues.put("requiresReview", requiresReview);
        metadataValues.put("reviewStatus", result.reviewStatus());
        metadataValues.put("automaticRejectionEnabled", result.automaticRejectionEnabled());
        metadataValues.put("confidenceDisplayEnabled", result.confidenceDisplayEnabled());
        metadataValues.put("manualReviewSignalEnabled", result.manualReviewSignalEnabled());
        metadataValues.put("needsClarification", understanding.needsClarification());
        metadataValues.put("clarificationOptions", understanding.clarificationOptions());
        putIfPresent(metadataValues, "canonicalQuestion", understanding.canonicalQuestion());
        putIfPresent(metadataValues, "canonicalQuestionDisplay", understanding.canonicalQuestionDisplay());
        putIfPresent(metadataValues, "modelInputQuestion", understanding.modelInputQuestion());
        putIfPresent(metadataValues, "normalizerVersion", understanding.normalizerVersion());
        putIfPresent(metadataValues, "matchedIntent", understanding.matchedIntent());
        putIfPresent(metadataValues, "scopeVerification", understanding.scopeVerification());
        putIfPresent(metadataValues, "reasonCode", understanding.reasonCode());
        putIfPresent(metadataValues, "interpretationNote", understanding.interpretationNote());
        putIfPresent(metadataValues, "displayAnswer", presentation.displayAnswer());
        putIfPresent(metadataValues, "displayLocale", presentation.displayLocale());
        String metadata = json(metadataValues);
        MessageEntity assistant = messages.save(new MessageEntity(
                conversation,
                invocation,
                "assistant",
                sourceType,
                content,
                metadata
        ));
        return new QuestionResponse(toMessage(userMessage), toMessage(assistant), result);
    }

    /**
     * The model service decides answer-shape validity against the frozen answer
     * vocabulary; the local regex only widens that for an older service build.
     */
    static boolean requiresManualReview(ApiPredictionResponse result) {
        return result.presentation().answerShapeMismatch()
                || hasAnswerShapeMismatch(result.predictedQuestionType(), result.answer());
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    static void verifyInputDigest(String storedSha256, String modelSha256) {
        if (modelSha256 == null || !modelSha256.equals(storedSha256)) {
            throw new ModelServiceException(
                    "模型服务返回的输入图像哈希与受控存储记录不一致；结果已拒绝保存。"
            );
        }
    }

    /**
     * Legacy fallback for a model-service that predates {@code answer_shape_mismatch}.
     *
     * <p>The service-side flag is authoritative because it is computed against the
     * matched intent and the frozen 55-class answer vocabulary; this only knows
     * digits, {@code m2} and yes/no.
     */
    static boolean hasAnswerShapeMismatch(String questionType, String answer) {
        if (questionType == null || answer == null || answer.isBlank()) return false;
        return switch (questionType) {
            case "count" -> !answer.matches("\\d+");
            case "area" -> !(answer.matches("\\d+m2") || answer.contains("m2"));
            case "presence", "comp", "comparison" -> !("yes".equals(answer) || "no".equals(answer));
            default -> false;
        };
    }

    private QuestionResponse askExternal(
            ConversationEntity conversation,
            ImageAssetEntity image,
            MessageEntity userMessage,
            String question,
            String providerId
    ) {
        UserEntity user = currentUser();
        boolean optedIn = userSettings.findByUserId(user.getId())
                .map(setting -> setting.isExternalImageOptIn())
                .orElse(false);
        if (!optedIn) {
            throw new RequestValidationException(
                    "发送图像到外部视觉 Provider 前，需要在“模型与设置”中显式开启许可。"
            );
        }
        AiProvider provider = aiProviders.stream()
                .filter(candidate -> candidate.descriptor().providerId().equals(providerId))
                .filter(candidate -> "EXTERNAL_VLM".equals(candidate.descriptor().kind()))
                .findFirst()
                .orElseThrow(() -> new RequestValidationException("外部视觉 Provider 不存在：" + providerId));
        if (!"CONFIGURED".equals(provider.descriptor().configurationState())) {
            throw new ProviderNotConfiguredException(
                    provider.descriptor().displayName() + " 尚未配置；不会使用浏览器登录状态代替 API 授权。"
            );
        }

        AiProvider.ProviderResult external = provider.invoke(new AiProvider.ProviderRequest(
                storage.read(image.getStorageKey()), image.getMimeType(), question, conversation.getId().toString()
        ));
        ModelInvocationEntity invocation = invocations.save(new ModelInvocationEntity(
                conversation,
                null,
                "EXTERNAL_VLM",
                external.modelId(),
                "external_vlm_assist",
                question,
                external.content(),
                "answered",
                null,
                null,
                "open_visual_assistance",
                "[]",
                "{}",
                external.latencyMs(),
                external.requestId(),
                external.promptTokens(),
                external.completionTokens(),
                external.totalTokens(),
                external.estimatedCostUsd()
        ));
        invocation.recordInferenceContract(
                image.getSha256(),
                "external_general_vision_assistance",
                json(List.of(
                        "该输出来自外部 " + provider.descriptor().displayName() + "，不属于论文研究模型结果。",
                        "不保证适用于专业遥感定量解译，重要结论需要人工核验。"
                )),
                "外部视觉辅助回答；与闭集 RS-VQA 研究模型严格分离。",
                "external_model_answer_requires_domain_verification",
                false,
                false,
                true
        );
        String metadata = json(Map.of(
                "providerId", external.providerId(),
                "providerModel", external.modelId(),
                "outputBoundary", "外部 " + provider.descriptor().displayName() + " 辅助输出，不属于论文研究模型预测。"
        ));
        MessageEntity assistant = messages.save(new MessageEntity(
                conversation, invocation, "assistant", "EXTERNAL_VLM", external.content(), metadata
        ));
        // The research canonicalizer is never applied to an external provider:
        // it receives the user's original question verbatim, so canonicalQuestion
        // stays null rather than echoing the raw text into a field that means
        // "this was rewritten into a verified RSVQA-HR form".
        ApiPredictionResponse response = new ApiPredictionResponse(
                external.requestId(),
                "answered",
                true,
                external.content(),
                null,
                null,
                List.of(),
                null,
                "open_visual_assistance",
                "open_visual_assistance",
                java.util.Map.of(),
                "external_vlm_assist",
                null,
                null,
                null,
                null,
                "external_general_vision_assistance",
                List.of(
                        "该输出来自外部 " + provider.descriptor().displayName() + "，不属于论文研究模型结果。",
                        "不保证适用于专业遥感定量解译，重要结论需要人工核验。"
                ),
                "外部通用视觉辅助回答；与闭集 RS-VQA 研究模型严格分离。",
                "external_model_answer_requires_domain_verification",
                false,
                false,
                true,
                image.getSha256(),
                external.latencyMs(),
                "external_provider",
                ApiPredictionResponse.QuestionUnderstanding.notApplicable(question),
                ApiPredictionResponse.AnswerPresentation.NONE
        );
        return new QuestionResponse(toMessage(userMessage), toMessage(assistant), response);
    }

    private ProjectResponse toProject(ProjectEntity project, UUID userId) {
        List<ConversationSummary> summaries = conversations
                .findByProjectIdAndArchivedFalseOrderByUpdatedAtDesc(project.getId())
                .stream()
                .map(conversation -> new ConversationSummary(
                        conversation.getId(),
                        conversation.getTitle(),
                        images.findByConversationId(conversation.getId()).isPresent(),
                        conversation.getUpdatedAt()
                ))
                .toList();
        return new ProjectResponse(project.getId(), project.getName(), summaries, project.getUpdatedAt());
    }

    private ConversationResponse toConversation(ConversationEntity conversation) {
        ImageResponse image = images.findByConversationId(conversation.getId()).map(this::toImage).orElse(null);
        return new ConversationResponse(
                conversation.getId(),
                conversation.getProject().getId(),
                conversation.getTitle(),
                image,
                messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                        .map(this::toMessage)
                        .toList(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private ImageResponse toImage(ImageAssetEntity image) {
        return new ImageResponse(
                image.getId(),
                image.getOriginalName(),
                image.getSha256(),
                image.getMimeType(),
                image.getSizeBytes(),
                image.getWidthPx(),
                image.getHeightPx(),
                "/api/v1/conversations/" + image.getConversation().getId() + "/image/content"
        );
    }

    private MessageResponse toMessage(MessageEntity message) {
        ModelInvocationEntity invocation = message.getModelInvocation();
        InvocationResponse invocationResponse = invocation == null ? null : new InvocationResponse(
                invocation.getId(),
                invocation.getRequestId(),
                invocation.getStatus(),
                invocation.getPredictionOrigin(),
                invocation.getModelReleaseId(),
                invocation.getProviderType(),
                invocation.getProviderModel(),
                invocation.getConfidence(),
                invocation.getMargin(),
                invocation.getLatencyMs(),
                invocation.getPromptTokens(),
                invocation.getCompletionTokens(),
                invocation.getTotalTokens(),
                invocation.getEstimatedCostUsd(),
                invocation.getCheckpointSha256(),
                invocation.getAnswerVocabularySha256(),
                invocation.getRuntimeArtifactSha256(),
                invocation.getInputSha256(),
                invocation.getTaskScope(),
                readList(invocation.getLimitationsJson()),
                invocation.getCapabilityNotice(),
                invocation.getReviewStatus(),
                invocation.isAutomaticRejectionEnabled(),
                invocation.isConfidenceDisplayEnabled(),
                invocation.isManualReviewSignalEnabled(),
                readTopK(invocation.getTopKJson()),
                readProbabilities(invocation.getQuestionTypeProbabilitiesJson()),
                invocation.getCanonicalQuestion(),
                invocation.getModelInputQuestion(),
                invocation.getQuestionNormalizerVersion(),
                invocation.getMatchedIntent(),
                invocation.getQuestionScopeVerification(),
                invocation.isAnswerShapeMismatch()
        );
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getSourceType(),
                message.getContent(),
                message.getMetadataJson(),
                invocationResponse,
                message.getCreatedAt()
        );
    }

    private ConversationEntity ownedConversation(UUID id) {
        return conversations.findByIdAndProjectUserId(id, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("会话不存在。"));
    }

    private ProjectEntity ownedProject(UUID id, UUID userId) {
        return projects.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String jsonEscape(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("模型 provenance 无法序列化。", error);
        }
    }

    private List<String> readList(String value) {
        return readJson(value, new TypeReference<>() {}, List.of());
    }

    private List<ModelPredictionResponse.TopKPrediction> readTopK(String value) {
        return readJson(value, new TypeReference<>() {}, List.of());
    }

    private Map<String, Double> readProbabilities(String value) {
        return readJson(value, new TypeReference<>() {}, Map.of());
    }

    private <T> T readJson(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            return fallback;
        }
    }

    private static String safeReportName(String title) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").trim();
        return safe.isBlank() ? "rs-vqa-report" : safe.substring(0, Math.min(safe.length(), 80));
    }

    public record ImageContent(byte[] bytes, String mimeType, String name) {
    }

    public record ReportContent(byte[] bytes, String name) {
    }
}
