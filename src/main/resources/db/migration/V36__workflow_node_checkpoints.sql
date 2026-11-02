CREATE TABLE workflow_run_state (
 execution_id BIGINT PRIMARY KEY REFERENCES workflow_execution(id) ON DELETE CASCADE,
 tenant_id BIGINT NOT NULL REFERENCES tenant(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
 chat_message_id BIGINT, model_config_id BIGINT, input_ciphertext TEXT NOT NULL, graph_digest VARCHAR(64) NOT NULL, graph_ciphertext TEXT NOT NULL,
 status VARCHAR(24) NOT NULL, owner_token VARCHAR(36),lease_until TIMESTAMPTZ,
 UNIQUE(tenant_id,user_id,chat_message_id)
);
CREATE TABLE workflow_node_checkpoint (
 execution_id BIGINT NOT NULL REFERENCES workflow_execution(id) ON DELETE CASCADE,
 node_key VARCHAR(160) NOT NULL,node_type VARCHAR(32) NOT NULL,status VARCHAR(24) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,result_ciphertext TEXT,error_code VARCHAR(128),
 started_at TIMESTAMPTZ,completed_at TIMESTAMPTZ,PRIMARY KEY(execution_id,node_key)
);
