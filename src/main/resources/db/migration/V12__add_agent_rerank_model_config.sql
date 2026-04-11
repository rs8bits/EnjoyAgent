-- Agent 增加可选的重排模型绑定，允许按 Agent 决定是否启用 rerank。
ALTER TABLE agent
    ADD COLUMN rerank_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rerank_model_config_id BIGINT,
    ADD CONSTRAINT fk_agent_rerank_model_config FOREIGN KEY (rerank_model_config_id) REFERENCES model_config (id);

CREATE INDEX idx_agent_rerank_model_config_id ON agent (rerank_model_config_id);
CREATE INDEX idx_agent_rerank_enabled ON agent (rerank_enabled);
