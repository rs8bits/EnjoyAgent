CREATE TABLE mcp_action_confirmation (
 id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL REFERENCES tenant(id), user_id BIGINT NOT NULL REFERENCES app_user(id),
 agent_id BIGINT, session_id BIGINT, scope_key VARCHAR(160) NOT NULL, tool_id BIGINT NOT NULL, tool_name VARCHAR(255) NOT NULL,
 tool_digest VARCHAR(64) NOT NULL, arguments_ciphertext TEXT NOT NULL, arguments_digest VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL, result_ciphertext TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 expires_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
 UNIQUE (tenant_id,user_id,scope_key,tool_id,arguments_digest)
);
CREATE INDEX idx_mcp_confirmation_pending ON mcp_action_confirmation(tenant_id,user_id,session_id,status);
