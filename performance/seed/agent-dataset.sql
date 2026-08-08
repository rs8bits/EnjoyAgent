\set ON_ERROR_STOP on

INSERT INTO agent (
    tenant_id,
    name,
    description,
    system_prompt,
    model_config_id,
    context_strategy,
    context_window_size,
    enabled,
    rerank_enabled,
    memory_enabled,
    memory_update_message_threshold,
    chat_model_binding_type,
    created_at,
    updated_at
)
SELECT :tenant_id,
       'perf-seed-' || lpad(series.id::text, 6, '0'),
       'Fixed performance baseline seed',
       'You are a performance baseline Agent.',
       :model_config_id,
       'SLIDING_WINDOW',
       10,
       true,
       false,
       false,
       6,
       'USER_MODEL',
       now(),
       now()
FROM generate_series(1, :agent_count) AS series(id)
ON CONFLICT (tenant_id, name) DO NOTHING;

SELECT count(*) AS seeded_agents
FROM agent
WHERE tenant_id = :tenant_id
  AND name LIKE 'perf-seed-%';
