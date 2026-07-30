ALTER TABLE batch_item
    RENAME COLUMN attempt_count TO attempt;

ALTER TABLE batch_item
    ADD COLUMN lease_owner VARCHAR(100),
    ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;

-- Releases created before lease support cannot prove ownership after a
-- process restart. Requeue them once and let the new claim protocol assign a
-- fresh owner and attempt.
UPDATE batch_item
SET status = 'QUEUED'
WHERE status = 'RUNNING';

UPDATE batch_job
SET status = 'QUEUED',
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'RUNNING';

ALTER TABLE batch_item
    ADD CONSTRAINT ck_batch_item_lease_state
    CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    );

CREATE INDEX idx_batch_item_claim_queue
    ON batch_item(batch_job_id, status, created_at, id);

CREATE INDEX idx_batch_item_expired_lease
    ON batch_item(lease_expires_at)
    WHERE status = 'RUNNING';
