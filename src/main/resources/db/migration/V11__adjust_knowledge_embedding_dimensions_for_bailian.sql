-- 将知识库向量维度调整为 1024，以兼容百炼 text-embedding-v3。
DROP INDEX IF EXISTS idx_knowledge_chunk_embedding_cosine;

ALTER TABLE knowledge_chunk
    ALTER COLUMN embedding TYPE VECTOR(1024)
        USING CASE
                  WHEN vector_dims(embedding) = 1024 THEN embedding::vector(1024)
                  ELSE subvector(embedding, 1, 1024)::vector(1024)
            END;

CREATE INDEX idx_knowledge_chunk_embedding_cosine
    ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
