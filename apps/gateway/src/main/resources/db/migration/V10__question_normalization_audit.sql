-- Question-normalization audit for the research-model path.
--
-- model_invocation.question already holds the verbatim user text, so no
-- original_question column is added; these record what the frozen RSVQA-HR
-- classifier was actually asked and by which normalizer version. External
-- vision providers leave every column here NULL.

ALTER TABLE model_invocation
    ADD COLUMN canonical_question TEXT,
    ADD COLUMN canonical_question_display TEXT,
    ADD COLUMN model_input_question TEXT,
    ADD COLUMN question_normalizer_version VARCHAR(40),
    ADD COLUMN matched_intent VARCHAR(40),
    ADD COLUMN matched_objects_json TEXT,
    ADD COLUMN question_scope_verification VARCHAR(40),
    ADD COLUMN reason_code VARCHAR(80),
    ADD COLUMN needs_clarification BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN clarification_options_json TEXT,
    ADD COLUMN display_answer TEXT,
    ADD COLUMN display_locale VARCHAR(20),
    ADD COLUMN answer_shape_mismatch BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_model_invocation_normalizer
    ON model_invocation (question_normalizer_version, question_scope_verification);
