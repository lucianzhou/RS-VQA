ALTER TABLE model_invocation
    ALTER COLUMN checkpoint_sha256 TYPE VARCHAR(64),
    ALTER COLUMN answer_vocabulary_sha256 TYPE VARCHAR(64),
    ALTER COLUMN runtime_artifact_sha256 TYPE VARCHAR(64);

ALTER TABLE batch_item
    ALTER COLUMN checkpoint_sha256 TYPE VARCHAR(64),
    ALTER COLUMN answer_vocabulary_sha256 TYPE VARCHAR(64),
    ALTER COLUMN runtime_artifact_sha256 TYPE VARCHAR(64);
