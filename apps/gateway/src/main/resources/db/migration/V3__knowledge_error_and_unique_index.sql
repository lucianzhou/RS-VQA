ALTER TABLE knowledge_document
    ADD COLUMN error_message TEXT;

CREATE INDEX idx_knowledge_chunk_document_ordinal ON knowledge_chunk(document_id, ordinal);
