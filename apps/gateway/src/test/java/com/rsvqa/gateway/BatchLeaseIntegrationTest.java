package com.rsvqa.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rsvqa.gateway.domain.BatchItemEntity;
import com.rsvqa.gateway.domain.BatchJobEntity;
import com.rsvqa.gateway.domain.UserEntity;
import com.rsvqa.gateway.repository.BatchItemRepository;
import com.rsvqa.gateway.repository.BatchJobRepository;
import com.rsvqa.gateway.repository.UserRepository;

@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=true",
        "spring.data.redis.repositories.enabled=false",
        "rsvqa.batch.recovery-enabled=false",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:15432/rsvqa",
        "spring.datasource.username=rsvqa",
        "spring.datasource.password=rsvqa_dev_only",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=16379"
})
@EnabledIfEnvironmentVariable(named = "RSVQA_COMPOSE_INTEGRATION", matches = "true")
class BatchLeaseIntegrationTest {

    private static final String IMAGE_SHA256 = "a".repeat(64);

    @Autowired
    private UserRepository users;

    @Autowired
    private BatchJobRepository jobs;

    @Autowired
    private BatchItemRepository items;

    @Autowired
    private BatchLeaseStore leases;

    @Autowired
    private BatchService batches;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void atomicallyClaimsDifferentItemsAcrossWorkers() throws Exception {
        Fixture fixture = fixture(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> leases.claim(
                    fixture.jobId(), "worker-a", Instant.now().plusSeconds(60)
            ));
            var second = executor.submit(() -> leases.claim(
                    fixture.jobId(), "worker-b", Instant.now().plusSeconds(60)
            ));
            Set<UUID> claimedIds = Set.of(
                    first.get(10, TimeUnit.SECONDS).orElseThrow().id(),
                    second.get(10, TimeUnit.SECONDS).orElseThrow().id()
            );

            assertThat(claimedIds).hasSize(2);
            assertThat(leases.claim(
                    fixture.jobId(), "worker-c", Instant.now().plusSeconds(60)
            )).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_item WHERE batch_job_id = ? AND status = 'RUNNING'",
                    Integer.class,
                    fixture.jobId()
            )).isEqualTo(2);
        } finally {
            executor.shutdownNow();
            cleanup(fixture);
        }
    }

    @Test
    void recoversExpiredLeaseAndRejectsStaleCompletion() {
        Fixture fixture = fixture(1);
        try {
            BatchLeaseStore.ClaimedBatchItem first = leases.claim(
                    fixture.jobId(), "old-worker", Instant.now().plusSeconds(60)
            ).orElseThrow();
            jdbc.update(
                    "UPDATE batch_item SET lease_expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    first.id()
            );

            assertThat(leases.recoverExpired(Instant.now())).containsExactly(fixture.jobId());
            BatchLeaseStore.ClaimedBatchItem second = leases.claim(
                    fixture.jobId(), "new-worker", Instant.now().plusSeconds(60)
            ).orElseThrow();

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(second.attempt()).isEqualTo(2);
            assertThat(batches.succeed(fixture.jobId(), work(first), prediction("stale"))).isFalse();
            assertThat(batches.succeed(fixture.jobId(), work(second), prediction("accepted"))).isTrue();

            Map<String, Object> item = jdbc.queryForMap(
                    "SELECT status, answer, attempt, lease_owner, lease_expires_at FROM batch_item WHERE id = ?",
                    first.id()
            );
            assertThat(item.get("status")).isEqualTo("COMPLETED");
            assertThat(item.get("answer")).isEqualTo("accepted");
            assertThat(item.get("attempt")).isEqualTo(2);
            assertThat(item.get("lease_owner")).isNull();
            assertThat(item.get("lease_expires_at")).isNull();
            assertThat(jdbc.queryForMap(
                    "SELECT status, completed_items, failed_items FROM batch_job WHERE id = ?",
                    fixture.jobId()
            )).containsEntry("status", "COMPLETED")
                    .containsEntry("completed_items", 1)
                    .containsEntry("failed_items", 0);
        } finally {
            cleanup(fixture);
        }
    }

    @Test
    void cancellationStopsNewClaimsAndExpiredWorkSettlesAsCancelled() {
        Fixture fixture = fixture(2);
        try {
            BatchLeaseStore.ClaimedBatchItem running = leases.claim(
                    fixture.jobId(), "worker-a", Instant.now().plusSeconds(60)
            ).orElseThrow();
            jdbc.update(
                    "UPDATE batch_job SET cancel_requested = TRUE WHERE id = ?",
                    fixture.jobId()
            );

            assertThat(leases.claim(
                    fixture.jobId(), "worker-b", Instant.now().plusSeconds(60)
            )).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_item WHERE batch_job_id = ? AND status = 'CANCELLED'",
                    Integer.class,
                    fixture.jobId()
            )).isEqualTo(1);

            jdbc.update(
                    "UPDATE batch_item SET lease_expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    running.id()
            );
            leases.recoverExpired(Instant.now());

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_item WHERE batch_job_id = ? AND status = 'RUNNING'",
                    Integer.class,
                    fixture.jobId()
            )).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM batch_job WHERE id = ?",
                    String.class,
                    fixture.jobId()
            )).isEqualTo("CANCELLED");
        } finally {
            cleanup(fixture);
        }
    }

    private Fixture fixture(int itemCount) {
        String suffix = UUID.randomUUID().toString();
        UserEntity user = users.save(new UserEntity("batch-" + suffix, "Batch lease", "USER", false));
        BatchJobEntity job = jobs.save(new BatchJobEntity(user, null, "test-release", itemCount));
        for (int index = 0; index < itemCount; index++) {
            items.save(new BatchItemEntity(
                    job,
                    new BatchItemEntity.FileDescriptor(
                            "test/" + suffix + "/" + index,
                            "image-" + index + ".png",
                            IMAGE_SHA256,
                            "image/png",
                            100,
                            10,
                            10
                    ),
                    "What is in this image?"
            ));
        }
        return new Fixture(user.getId(), job.getId());
    }

    private void cleanup(Fixture fixture) {
        jdbc.update("DELETE FROM batch_item WHERE batch_job_id = ?", fixture.jobId());
        jdbc.update("DELETE FROM batch_job WHERE id = ?", fixture.jobId());
        jdbc.update("DELETE FROM app_user WHERE id = ?", fixture.userId());
    }

    private static BatchService.BatchWorkItem work(BatchLeaseStore.ClaimedBatchItem item) {
        return new BatchService.BatchWorkItem(
                item.id(),
                item.attempt(),
                item.modelReleaseId(),
                item.storageKey(),
                item.filename(),
                item.contentType(),
                item.question(),
                item.leaseOwner()
        );
    }

    private static ApiPredictionResponse prediction(String answer) {
        return new ApiPredictionResponse(
                "request-" + answer,
                "ok",
                true,
                answer,
                0.9,
                0.5,
                List.of(),
                "What is in this image?",
                "presence",
                "presence",
                Map.of("presence", 1.0),
                "research_rsvqa",
                "test-release",
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "rsvqa_hr_grouped_closed_set",
                List.of(),
                "test",
                "review",
                false,
                true,
                true,
                IMAGE_SHA256,
                10L,
                "real",
                new ApiPredictionResponse.QuestionUnderstanding(
                        "What is in this image?",
                        "What is in this image?",
                        "What is in this image?",
                        "What is in this image?",
                        "test",
                        "presence",
                        List.of(),
                        "verified",
                        null,
                        false,
                        List.of(),
                        null
                ),
                new ApiPredictionResponse.AnswerPresentation(answer, "en", false)
        );
    }

    private record Fixture(UUID userId, UUID jobId) {
    }
}
