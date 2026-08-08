ALTER TABLE knowledge_document
    ADD COLUMN processing_token VARCHAR(36),
    ADD COLUMN processing_fence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN processing_lease_until TIMESTAMPTZ;

CREATE INDEX idx_knowledge_document_processing_lease
    ON knowledge_document (status, processing_lease_until, id)
    WHERE status IN ('PROCESSING', 'INDEXING');
