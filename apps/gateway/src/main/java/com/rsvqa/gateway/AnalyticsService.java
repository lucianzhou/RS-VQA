package com.rsvqa.gateway;

import static com.rsvqa.gateway.AnalyticsDtos.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rsvqa.gateway.domain.BatchItemEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
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

@Service
public class AnalyticsService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.65;

    private final UserRepository users;
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final ImageAssetRepository images;
    private final ModelInvocationRepository invocations;
    private final BatchJobRepository batchJobs;
    private final BatchItemRepository batchItems;

    public AnalyticsService(
            UserRepository users,
            ProjectRepository projects,
            ConversationRepository conversations,
            ImageAssetRepository images,
            ModelInvocationRepository invocations,
            BatchJobRepository batchJobs,
            BatchItemRepository batchItems
    ) {
        this.users = users;
        this.projects = projects;
        this.conversations = conversations;
        this.images = images;
        this.invocations = invocations;
        this.batchJobs = batchJobs;
        this.batchItems = batchItems;
    }

    @Transactional(readOnly = true)
    public AnalysisStatistics project(UUID projectId) {
        UUID userId = currentUser().getId();
        ProjectEntity project = projects.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
        List<ConversationEntity> projectConversations = conversations.findByProjectIdOrderByUpdatedAtDesc(projectId);
        Map<UUID, String> conversationNames = projectConversations.stream()
                .collect(Collectors.toMap(ConversationEntity::getId, ConversationEntity::getTitle));
        List<ModelInvocationEntity> calls = invocations.findByConversationProjectIdOrderByCreatedAtAsc(projectId);
        List<Fact> facts = calls.stream()
                .map(call -> new Fact(
                        call.getId(),
                        conversationNames.getOrDefault(call.getConversation().getId(), "已归档会话"),
                        call.getQuestion(),
                        call.getAnswer(),
                        call.getStatus(),
                        call.getPredictionOrigin(),
                        call.getModelReleaseId(),
                        resolvedQuestionType(call.getPredictedQuestionType(), call.getQuestion()),
                        call.getConfidence(),
                        call.getMargin(),
                        call.getRequestId()
                ))
                .toList();
        int imageCount = (int) projectConversations.stream()
                .filter(conversation -> images.findByConversationId(conversation.getId()).isPresent())
                .count();
        return statistics(
                "PROJECT",
                projectId,
                project.getName(),
                projectConversations.size(),
                imageCount,
                facts
        );
    }

    @Transactional(readOnly = true)
    public AnalysisStatistics batch(UUID batchJobId) {
        UUID userId = currentUser().getId();
        BatchJobEntity job = batchJobs.findByIdAndUserId(batchJobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("批量任务不存在。"));
        List<BatchItemEntity> items = batchItems.findByBatchJobIdOrderByCreatedAtAsc(batchJobId);
        List<Fact> facts = items.stream()
                .map(item -> new Fact(
                        item.getId(),
                        item.getOriginalName(),
                        item.getQuestion(),
                        item.getAnswer(),
                        item.getStatus(),
                        item.getPredictionOrigin(),
                        item.getModelReleaseId() == null ? job.getModelReleaseId() : item.getModelReleaseId(),
                        resolvedQuestionType(item.getPredictedQuestionType(), item.getQuestion()),
                        item.getConfidence(),
                        item.getMargin(),
                        item.getRequestId()
                ))
                .toList();
        int imageCount = (int) items.stream().map(BatchItemEntity::getOriginalName).distinct().count();
        return statistics(
                "BATCH_JOB",
                batchJobId,
                "批量任务 " + batchJobId.toString().substring(0, 8),
                0,
                imageCount,
                facts
        );
    }

    private AnalysisStatistics statistics(
            String scopeType,
            UUID scopeId,
            String scopeName,
            int conversationCount,
            int imageCount,
            List<Fact> facts
    ) {
        int answered = (int) facts.stream().filter(Fact::answered).count();
        int unsupported = (int) facts.stream().filter(Fact::unsupported).count();
        int failed = (int) facts.stream().filter(Fact::failed).count();
        int lowConfidence = (int) facts.stream().filter(Fact::lowConfidence).count();
        List<Double> confidences = facts.stream().map(Fact::confidence).filter(Objects::nonNull).toList();
        List<Double> margins = facts.stream().map(Fact::margin).filter(Objects::nonNull).toList();

        List<AnalysisCase> representative = facts.stream()
                .filter(Fact::answered)
                .limit(6)
                .map(Fact::toCase)
                .toList();
        List<AnalysisCase> review = facts.stream()
                .filter(fact -> fact.lowConfidence() || fact.unsupported() || fact.failed())
                .limit(20)
                .map(Fact::toCase)
                .toList();

        return new AnalysisStatistics(
                scopeType,
                scopeId,
                scopeName,
                conversationCount,
                imageCount,
                facts.size(),
                answered,
                unsupported,
                failed,
                lowConfidence,
                average(confidences),
                average(margins),
                distribution(facts, Fact::questionType, false),
                distribution(facts.stream().filter(Fact::answered).toList(), fact -> blankAs(fact.answer(), "无答案"), true),
                distribution(facts, fact -> blankAs(fact.predictionOrigin(), "unknown"), false),
                confidenceDistribution(facts),
                facts.stream().map(Fact::modelReleaseId).filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().sorted().toList(),
                representative,
                review,
                "全部计数、比例、均值和分布由 Java 对当前用户可访问的持久化记录确定性计算；低置信度阈值固定为 0.65。"
        );
    }

    private static Map<String, Long> confidenceDistribution(List<Fact> facts) {
        Map<String, Long> bins = new LinkedHashMap<>();
        bins.put("[0,0.5)", 0L);
        bins.put("[0.5,0.65)", 0L);
        bins.put("[0.65,0.8)", 0L);
        bins.put("[0.8,1.0]", 0L);
        for (Fact fact : facts) {
            if (fact.confidence() == null) continue;
            String key = fact.confidence() < 0.5 ? "[0,0.5)"
                    : fact.confidence() < 0.65 ? "[0.5,0.65)"
                    : fact.confidence() < 0.8 ? "[0.65,0.8)"
                    : "[0.8,1.0]";
            bins.computeIfPresent(key, (ignored, count) -> count + 1);
        }
        return bins;
    }

    private static Map<String, Long> distribution(List<Fact> facts, Function<Fact, String> classifier, boolean limit) {
        Map<String, Long> counts = facts.stream()
                .collect(Collectors.groupingBy(classifier, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit ? 20 : Long.MAX_VALUE)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private static Double average(List<Double> values) {
        if (values.isEmpty()) return null;
        double value = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    static String resolvedQuestionType(String stored, String question) {
        if (stored != null && !stored.isBlank()) return stored.toLowerCase(Locale.ROOT);
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (normalized.contains("area") || normalized.contains("面积") || normalized.contains("占比") || normalized.contains("覆盖")) return "area";
        if (normalized.contains("more") || normalized.contains("less") || normalized.contains("比较") || normalized.contains("更多") || normalized.contains("更少")) return "comparison";
        if (normalized.contains("how many") || normalized.contains("多少") || normalized.contains("数量") || normalized.contains("几")) return "count";
        if (normalized.contains("is there") || normalized.contains("are there") || normalized.contains("有没有") || normalized.contains("是否") || normalized.contains("存在")) return "presence";
        return "other";
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private static String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Fact(
            UUID id,
            String label,
            String question,
            String answer,
            String status,
            String predictionOrigin,
            String modelReleaseId,
            String questionType,
            Double confidence,
            Double margin,
            String requestId
    ) {
        boolean answered() {
            return "answered".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
        }

        boolean unsupported() {
            return "unsupported".equalsIgnoreCase(status) || "rejected".equalsIgnoreCase(status);
        }

        boolean failed() {
            return "FAILED".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status);
        }

        boolean lowConfidence() {
            return answered() && confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD;
        }

        AnalysisCase toCase() {
            return new AnalysisCase(id, label, question, answer, status, predictionOrigin, modelReleaseId,
                    questionType, confidence, margin, requestId);
        }
    }
}
