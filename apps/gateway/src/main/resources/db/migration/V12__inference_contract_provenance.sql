-- Product-aligned inference contract provenance.
--
-- Confidence and margin are descriptive model outputs, not automatic risk
-- thresholds. These fields preserve the frozen runtime contract and the input
-- digest needed to replay or audit an invocation.

ALTER TABLE model_invocation
    ADD COLUMN input_sha256 VARCHAR(64),
    ADD COLUMN task_scope VARCHAR(200),
    ADD COLUMN limitations_json TEXT,
    ADD COLUMN capability_notice TEXT,
    ADD COLUMN review_status VARCHAR(80),
    ADD COLUMN automatic_rejection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN confidence_display_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN manual_review_signal_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_model_invocation_input_sha256
    ON model_invocation (input_sha256);

ALTER TABLE batch_item
    ADD COLUMN top_k_json TEXT,
    ADD COLUMN question_type_probabilities_json TEXT,
    ADD COLUMN canonical_question TEXT,
    ADD COLUMN model_input_question TEXT,
    ADD COLUMN question_normalizer_version VARCHAR(40),
    ADD COLUMN matched_intent VARCHAR(40),
    ADD COLUMN question_scope_verification VARCHAR(40),
    ADD COLUMN answer_shape_mismatch BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN task_scope VARCHAR(200),
    ADD COLUMN limitations_json TEXT,
    ADD COLUMN capability_notice TEXT,
    ADD COLUMN review_status VARCHAR(80),
    ADD COLUMN automatic_rejection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN confidence_display_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN manual_review_signal_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_batch_item_review_status
    ON batch_item (review_status, status);
