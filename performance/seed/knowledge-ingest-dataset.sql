\set ON_ERROR_STOP on

INSERT INTO model_config (
    tenant_id,
    name,
    provider,
    model_type,
    model_name,
    credential_id,
    credential_source,
    enabled
)
VALUES (
    :tenant_id,
    'perf-embedding-mock',
    'OPENAI',
    'EMBEDDING',
    'text-embedding-mock',
    NULL,
    'PLATFORM',
    TRUE
)
ON CONFLICT (tenant_id, name) DO UPDATE
SET provider = EXCLUDED.provider,
    model_type = EXCLUDED.model_type,
    model_name = EXCLUDED.model_name,
    credential_id = NULL,
    credential_source = 'PLATFORM',
    enabled = TRUE,
    updated_at = NOW();

INSERT INTO knowledge_base (
    tenant_id,
    name,
    description,
    embedding_model_config_id,
    enabled
)
SELECT
    :tenant_id,
    'perf-knowledge-ingest',
    'Reproducible synchronous versus asynchronous ingestion benchmark',
    model_config.id,
    TRUE
FROM model_config
WHERE model_config.tenant_id = :tenant_id
  AND model_config.name = 'perf-embedding-mock'
ON CONFLICT (tenant_id, name) DO UPDATE
SET description = EXCLUDED.description,
    embedding_model_config_id = EXCLUDED.embedding_model_config_id,
    enabled = TRUE,
    updated_at = NOW();

SELECT knowledge_base.id AS knowledge_base_id,
       knowledge_base.tenant_id,
       model_config.id AS embedding_model_config_id
FROM knowledge_base
JOIN model_config ON model_config.id = knowledge_base.embedding_model_config_id
WHERE knowledge_base.tenant_id = :tenant_id
  AND knowledge_base.name = 'perf-knowledge-ingest';
