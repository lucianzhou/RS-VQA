ALTER TABLE knowledge_document
    ADD COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'PRIVATE';

ALTER TABLE knowledge_document
    ADD CONSTRAINT chk_knowledge_document_scope
        CHECK (scope IN ('PRIVATE', 'PUBLIC'));

CREATE INDEX idx_knowledge_document_visibility
    ON knowledge_document(scope, user_id, index_version, created_at DESC);

CREATE UNIQUE INDEX uq_knowledge_public_release
    ON knowledge_document(sha256, index_version)
    WHERE scope = 'PUBLIC';
