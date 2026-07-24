ALTER TABLE batch_item
    ADD COLUMN storage_key VARCHAR(500),
    ADD COLUMN original_name VARCHAR(255),
    ADD COLUMN sha256 CHAR(64),
    ADD COLUMN mime_type VARCHAR(80),
    ADD COLUMN size_bytes BIGINT,
    ADD COLUMN width_px INTEGER,
    ADD COLUMN height_px INTEGER,
    ADD COLUMN prediction_origin VARCHAR(80),
    ADD COLUMN confidence DOUBLE PRECISION,
    ADD COLUMN latency_ms BIGINT,
    ADD COLUMN error_message TEXT;

CREATE INDEX idx_batch_job_user_created ON batch_job(user_id, created_at DESC);
CREATE INDEX idx_batch_item_job_status ON batch_item(batch_job_id, status);
CREATE INDEX idx_agent_run_user_created ON agent_run(user_id, created_at DESC);
CREATE INDEX idx_knowledge_document_user_created ON knowledge_document(user_id, created_at DESC);
