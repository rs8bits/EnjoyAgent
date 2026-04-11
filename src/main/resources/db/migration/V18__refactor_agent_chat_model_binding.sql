ALTER TABLE agent
    ADD COLUMN chat_model_binding_type VARCHAR(32);

UPDATE agent
SET chat_model_binding_type = 'USER_MODEL'
WHERE chat_model_binding_type IS NULL;

ALTER TABLE agent
    ALTER COLUMN chat_model_binding_type SET NOT NULL;

ALTER TABLE agent
    ALTER COLUMN model_config_id DROP NOT NULL;

ALTER TABLE agent
    ADD COLUMN official_model_config_id BIGINT;

ALTER TABLE agent
    ADD CONSTRAINT fk_agent_official_model_config FOREIGN KEY (official_model_config_id) REFERENCES official_model_config (id);

CREATE INDEX idx_agent_chat_model_binding_type ON agent (chat_model_binding_type);
CREATE INDEX idx_agent_official_model_config_id ON agent (official_model_config_id);
