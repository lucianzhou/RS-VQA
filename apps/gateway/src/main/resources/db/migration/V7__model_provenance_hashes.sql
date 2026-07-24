ALTER TABLE model_invocation
    ADD COLUMN checkpoint_sha256 CHAR(64),
    ADD COLUMN answer_vocabulary_sha256 CHAR(64),
    ADD COLUMN runtime_artifact_sha256 CHAR(64);

ALTER TABLE batch_item
    ADD COLUMN checkpoint_sha256 CHAR(64),
    ADD COLUMN answer_vocabulary_sha256 CHAR(64),
    ADD COLUMN runtime_artifact_sha256 CHAR(64);
