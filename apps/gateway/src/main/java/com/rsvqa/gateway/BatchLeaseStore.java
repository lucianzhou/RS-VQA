package com.rsvqa.gateway;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class BatchLeaseStore {

    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT item.id
                FROM batch_item item
                JOIN batch_job job ON job.id = item.batch_job_id
                WHERE item.batch_job_id = :jobId
                  AND item.status = 'QUEUED'
                  AND job.cancel_requested = FALSE
                  AND job.archived = FALSE
                ORDER BY item.created_at, item.id
                FOR UPDATE OF item SKIP LOCKED
                LIMIT 1
            )
            UPDATE batch_item item
            SET status = 'RUNNING',
                lease_owner = :leaseOwner,
                lease_expires_at = :leaseExpiresAt,
                attempt = item.attempt + 1,
                error_code = NULL,
                error_message = NULL,
                updated_at = CURRENT_TIMESTAMP
            FROM candidate
            WHERE item.id = candidate.id
            RETURNING item.id, item.attempt
            """;

    private static final String CLAIMED_ITEM_SQL = """
            SELECT item.id,
                   item.attempt,
                   item.storage_key,
                   item.original_name,
                   item.mime_type,
                   item.question,
                   job.model_release_id
            FROM batch_item item
            JOIN batch_job job ON job.id = item.batch_job_id
            WHERE item.id = :itemId
              AND item.status = 'RUNNING'
              AND item.lease_owner = :leaseOwner
            """;

    private static final String REFRESH_JOB_SQL = """
            WITH item_counts AS (
                SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS succeeded,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                       COUNT(*) FILTER (WHERE status = 'QUEUED') AS queued,
                       COUNT(*) FILTER (WHERE status = 'RUNNING') AS running
                FROM batch_item
                WHERE batch_job_id = :jobId
            )
            UPDATE batch_job job
            SET completed_items = item_counts.succeeded + item_counts.failed,
                failed_items = item_counts.failed,
                status = CASE
                    WHEN job.cancel_requested
                         AND item_counts.running = 0
                         AND item_counts.queued = 0
                        THEN 'CANCELLED'
                    WHEN item_counts.running > 0 THEN 'RUNNING'
                    WHEN item_counts.queued > 0 THEN 'QUEUED'
                    WHEN item_counts.failed > 0 THEN 'COMPLETED_WITH_ERRORS'
                    ELSE 'COMPLETED'
                END,
                updated_at = CURRENT_TIMESTAMP
            FROM item_counts
            WHERE job.id = :jobId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    BatchLeaseStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    Optional<ClaimedBatchItem> claim(UUID jobId, String leaseOwner, Instant leaseExpiresAt) {
        cancelQueuedIfRequested(jobId);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("leaseOwner", leaseOwner)
                .addValue("leaseExpiresAt", Timestamp.from(leaseExpiresAt));
        List<ClaimIdentity> claimed = jdbc.query(
                CLAIM_SQL,
                parameters,
                (result, row) -> new ClaimIdentity(
                        result.getObject("id", UUID.class),
                        result.getInt("attempt")
                )
        );
        if (claimed.isEmpty()) {
            refreshJob(jobId);
            return Optional.empty();
        }
        ClaimIdentity identity = claimed.getFirst();
        jdbc.update(
                "UPDATE batch_job SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP WHERE id = :jobId",
                Map.of("jobId", jobId)
        );
        List<ClaimedBatchItem> details = jdbc.query(
                CLAIMED_ITEM_SQL,
                new MapSqlParameterSource()
                        .addValue("itemId", identity.id())
                        .addValue("leaseOwner", leaseOwner),
                (result, row) -> new ClaimedBatchItem(
                        result.getObject("id", UUID.class),
                        result.getInt("attempt"),
                        result.getString("model_release_id"),
                        result.getString("storage_key"),
                        result.getString("original_name"),
                        result.getString("mime_type"),
                        result.getString("question"),
                        leaseOwner
                )
        );
        if (details.size() != 1) {
            throw new IllegalStateException("批量任务租约领取后无法读取唯一任务项。");
        }
        return Optional.of(details.getFirst());
    }

    @Transactional
    Set<UUID> recoverExpired(Instant now) {
        List<UUID> recovered = jdbc.query(
                """
                WITH recovered AS (
                    UPDATE batch_item item
                    SET status = CASE
                            WHEN job.cancel_requested THEN 'CANCELLED'
                            ELSE 'QUEUED'
                        END,
                        lease_owner = NULL,
                        lease_expires_at = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    FROM batch_job job
                    WHERE item.batch_job_id = job.id
                      AND item.status = 'RUNNING'
                      AND item.lease_expires_at < :now
                    RETURNING item.batch_job_id
                )
                SELECT DISTINCT batch_job_id FROM recovered
                """,
                Map.of("now", Timestamp.from(now)),
                (result, row) -> result.getObject("batch_job_id", UUID.class)
        );
        Set<UUID> jobIds = new LinkedHashSet<>(recovered);
        jobIds.forEach(jobId -> {
            cancelQueuedIfRequested(jobId);
            refreshJob(jobId);
        });
        return jobIds;
    }

    @Transactional(readOnly = true)
    List<UUID> runnableJobIds() {
        return jdbc.query(
                """
                SELECT DISTINCT job.id
                FROM batch_job job
                JOIN batch_item item ON item.batch_job_id = job.id
                WHERE item.status = 'QUEUED'
                  AND job.cancel_requested = FALSE
                  AND job.archived = FALSE
                  AND NOT EXISTS (
                      SELECT 1
                      FROM batch_item active
                      WHERE active.batch_job_id = job.id
                        AND active.status = 'RUNNING'
                  )
                ORDER BY job.id
                """,
                (result, row) -> result.getObject("id", UUID.class)
        );
    }

    @Transactional
    void cancelQueuedIfRequested(UUID jobId) {
        jdbc.update(
                """
                UPDATE batch_item item
                SET status = 'CANCELLED',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                FROM batch_job job
                WHERE item.batch_job_id = job.id
                  AND job.id = :jobId
                  AND job.cancel_requested = TRUE
                  AND item.status = 'QUEUED'
                """,
                Map.of("jobId", jobId)
        );
    }

    @Transactional
    void refreshJob(UUID jobId) {
        jdbc.update(REFRESH_JOB_SQL, Map.of("jobId", jobId));
    }

    record ClaimedBatchItem(
            UUID id,
            int attempt,
            String modelReleaseId,
            String storageKey,
            String filename,
            String contentType,
            String question,
            String leaseOwner
    ) {
    }

    private record ClaimIdentity(UUID id, int attempt) {
    }
}
