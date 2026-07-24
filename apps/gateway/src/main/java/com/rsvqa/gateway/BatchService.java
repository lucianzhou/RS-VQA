package com.rsvqa.gateway;

import static com.rsvqa.gateway.BatchDtos.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.rsvqa.gateway.domain.BatchItemEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.ProjectEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.ProjectRepository;
import com.rsvqa.gateway.repository.UserRepository;

@Service
public class BatchService {

    static final int MAX_IMAGES = 200;
    static final int MAX_QUESTIONS = 32;
    static final int MAX_COMBINATIONS = 1000;
    static final long MAX_TOTAL_IMAGE_BYTES = 120L * 1024 * 1024;

    private final UserRepository users;
    private final ProjectRepository projects;
    private final BatchJobRepository jobs;
    private final BatchItemRepository items;
    private final FileStorageService storage;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public BatchService(
            UserRepository users,
            ProjectRepository projects,
            BatchJobRepository jobs,
            BatchItemRepository items,
            FileStorageService storage,
            ObjectProvider<StringRedisTemplate> redisProvider
    ) {
        this.users = users;
        this.projects = projects;
        this.jobs = jobs;
        this.items = items;
        this.storage = storage;
        this.redisProvider = redisProvider;
    }

    @Transactional
    public BatchJobResponse create(
            UUID projectId,
            String modelReleaseId,
            List<MultipartFile> uploads,
            List<String> rawQuestions
    ) {
        UserEntity user = currentUser();
        List<String> questions = rawQuestions.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (uploads.isEmpty() || questions.isEmpty()) {
            throw new RequestValidationException("至少需要一张图像和一个非空问题。");
        }
        if (uploads.size() > MAX_IMAGES || questions.size() > MAX_QUESTIONS
                || uploads.size() * questions.size() > MAX_COMBINATIONS) {
            throw new RequestValidationException("单个批量任务最多 200 张图、32 个问题和 1000 个组合。");
        }
        long totalBytes = uploads.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
            throw new RequestValidationException("单个批量任务的图像总量不能超过 120 MiB。");
        }
        ProjectEntity project = projectId == null ? null : projects.findByIdAndUserId(projectId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("项目不存在。"));
        BatchJobEntity job = jobs.save(new BatchJobEntity(
                user,
                project,
                modelReleaseId == null || modelReleaseId.isBlank() ? null : modelReleaseId,
                uploads.size() * questions.size()
        ));
        List<FileStorageService.StoredImage> stored = new ArrayList<>();
        try {
            for (MultipartFile upload : uploads) {
                stored.add(storage.storeBatch(user.getId(), job.getId(), upload));
            }
            for (FileStorageService.StoredImage image : stored) {
                BatchItemEntity.FileDescriptor file = new BatchItemEntity.FileDescriptor(
                        image.storageKey(), image.originalName(), image.sha256(), image.mimeType(),
                        image.sizeBytes(), image.width(), image.height()
                );
                for (String question : questions) {
                    items.save(new BatchItemEntity(job, file, question));
                }
            }
        } catch (RuntimeException error) {
            stored.forEach(image -> storage.delete(image.storageKey()));
            throw error;
        }
        cacheProgress(job);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public List<BatchJobResponse> list() {
        UUID userId = currentUser().getId();
        return jobs.findByUserIdAndArchivedFalseOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<BatchJobResponse> archive() {
        UUID userId = currentUser().getId();
        return jobs.findByUserIdAndArchivedTrueOrderByCreatedAtDesc(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void archive(UUID jobId) {
        BatchJobEntity job = ownedJob(jobId);
        if ("QUEUED".equals(job.getStatus()) || "RUNNING".equals(job.getStatus())) {
            throw new RequestValidationException("运行中的批量任务不能归档，请先取消或等待完成。");
        }
        job.archive();
    }

    @Transactional
    public void restore(UUID jobId) {
        ownedJob(jobId).restore();
    }

    @Transactional(readOnly = true)
    public BatchJobResponse get(UUID jobId) {
        return toResponse(ownedJob(jobId));
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv(UUID jobId) {
        BatchJobResponse job = toResponse(ownedJob(jobId));
        StringBuilder csv = new StringBuilder("\uFEFFimage,question,status,answer,origin,confidence,latency_ms,error_code,attempt_count\n");
        for (BatchItemResponse item : job.items()) {
            csv.append(csv(item.imageName())).append(',')
                    .append(csv(item.question())).append(',')
                    .append(csv(item.status())).append(',')
                    .append(csv(item.answer())).append(',')
                    .append(csv(item.predictionOrigin())).append(',')
                    .append(item.confidence() == null ? "" : item.confidence()).append(',')
                    .append(item.latencyMs() == null ? "" : item.latencyMs()).append(',')
                    .append(csv(item.errorCode())).append(',')
                    .append(item.attemptCount()).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Transactional
    public void begin(UUID jobId) {
        BatchJobEntity job = jobs.findById(jobId).orElseThrow();
        if ("QUEUED".equals(job.getStatus())) {
            job.start();
            cacheProgress(job);
        }
    }

    @Transactional
    public Optional<BatchWorkItem> claimNext(UUID jobId) {
        BatchJobEntity job = jobs.findById(jobId).orElseThrow();
        if (job.isCancelRequested()) {
            items.findByBatchJobIdAndStatusOrderByCreatedAtAsc(jobId, "QUEUED")
                    .forEach(BatchItemEntity::cancel);
            job.complete();
            cacheProgress(job);
            return Optional.empty();
        }
        List<BatchItemEntity> queued = items.findByBatchJobIdAndStatusOrderByCreatedAtAsc(jobId, "QUEUED");
        if (queued.isEmpty()) {
            job.complete();
            cacheProgress(job);
            return Optional.empty();
        }
        BatchItemEntity item = queued.getFirst();
        item.start();
        return Optional.of(new BatchWorkItem(
                item.getId(),
                job.getModelReleaseId(),
                item.getStorageKey(),
                item.getOriginalName(),
                item.getMimeType(),
                item.getQuestion()
        ));
    }

    @Transactional
    public void succeed(UUID jobId, UUID itemId, ApiPredictionResponse result) {
        BatchJobEntity job = jobs.findById(jobId).orElseThrow();
        BatchItemEntity item = items.findById(itemId).orElseThrow();
        item.succeed(
                result.answer() == null ? result.capabilityNotice() : result.answer(),
                result.predictionOrigin(),
                result.confidence(),
                result.margin(),
                result.predictedQuestionType(),
                result.requestId(),
                result.modelReleaseId(),
                result.checkpointSha256(),
                result.answerVocabularySha256(),
                result.runtimeArtifactSha256(),
                result.latencyMs()
        );
        job.recordSuccess();
        cacheProgress(job);
    }

    @Transactional
    public void fail(UUID jobId, UUID itemId, RuntimeException error) {
        BatchJobEntity job = jobs.findById(jobId).orElseThrow();
        BatchItemEntity item = items.findById(itemId).orElseThrow();
        item.fail(error instanceof ModelServiceException ? "MODEL_SERVICE_ERROR" : "BATCH_ITEM_ERROR", error.getMessage());
        job.recordFailure();
        cacheProgress(job);
    }

    @Transactional
    public BatchJobResponse cancel(UUID jobId) {
        BatchJobEntity job = ownedJob(jobId);
        if (!job.getStatus().startsWith("COMPLETED") && !"CANCELLED".equals(job.getStatus())) {
            job.requestCancel();
        }
        cacheProgress(job);
        return toResponse(job);
    }

    @Transactional
    public BatchJobResponse retryFailed(UUID jobId) {
        BatchJobEntity job = ownedJob(jobId);
        List<BatchItemEntity> failed = items.findByBatchJobIdAndStatusOrderByCreatedAtAsc(jobId, "FAILED");
        if (failed.isEmpty()) {
            throw new RequestValidationException("当前任务没有可重试的失败项。");
        }
        failed.forEach(BatchItemEntity::queueForRetry);
        job.retry(failed.size());
        cacheProgress(job);
        return toResponse(job);
    }

    byte[] read(String storageKey) {
        return storage.read(storageKey);
    }

    private BatchJobEntity ownedJob(UUID jobId) {
        return jobs.findByIdAndUserId(jobId, currentUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("批量任务不存在。"));
    }

    private UserEntity currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return users.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("当前用户不存在。"));
    }

    private BatchJobResponse toResponse(BatchJobEntity job) {
        List<BatchItemResponse> responses = items.findByBatchJobIdOrderByCreatedAtAsc(job.getId()).stream()
                .map(item -> new BatchItemResponse(
                        item.getId(), item.getOriginalName(), item.getQuestion(), item.getStatus(), item.getAnswer(),
                        item.getPredictionOrigin(), item.getConfidence(), item.getMargin(), item.getPredictedQuestionType(),
                        item.getRequestId(), item.getModelReleaseId(), item.getCheckpointSha256(),
                        item.getAnswerVocabularySha256(), item.getRuntimeArtifactSha256(), item.getLatencyMs(), item.getErrorCode(),
                        item.getErrorMessage(), item.getAttemptCount()
                ))
                .toList();
        int progress = job.getTotalItems() == 0 ? 0 : (int) Math.round(job.getCompletedItems() * 100.0 / job.getTotalItems());
        return new BatchJobResponse(
                job.getId(), job.getStatus(), job.getTotalItems(), job.getCompletedItems(), job.getFailedItems(),
                job.isCancelRequested(), job.isArchived(), job.getModelReleaseId(), progress, responses, job.getCreatedAt(), job.getUpdatedAt()
        );
    }

    private void cacheProgress(BatchJobEntity job) {
        try {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.opsForValue().set(
                        "rsvqa:batch:" + job.getId() + ":progress",
                        job.getCompletedItems() + "/" + job.getTotalItems() + ":" + job.getStatus(),
                        Duration.ofHours(6)
                );
            }
        } catch (RuntimeException ignored) {
            // PostgreSQL is authoritative; Redis is an acceleration/coordination layer only.
        }
    }

    private static String csv(Object value) {
        if (value == null) return "";
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    public record BatchWorkItem(
            UUID id,
            String modelReleaseId,
            String storageKey,
            String filename,
            String contentType,
            String question
    ) {
    }
}
