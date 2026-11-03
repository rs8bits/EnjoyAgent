CREATE TABLE file_asset (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    original_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INPUT','ARTIFACT')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('UPLOADING','READY','FAILED','DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_file_asset_owner ON file_asset(tenant_id,user_id,created_at DESC);

CREATE TABLE sandbox_task (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('DEMO','PYTHON')),
    code_ciphertext TEXT NOT NULL,
    step_key VARCHAR(128) NOT NULL,
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 300),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    manager_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    next_poll_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    UNIQUE(tenant_id,user_id,idempotency_key)
);
CREATE INDEX idx_sandbox_task_dispatch ON sandbox_task(next_poll_at) WHERE completed_at IS NULL;
CREATE INDEX idx_sandbox_task_owner ON sandbox_task(tenant_id,user_id,created_at DESC);

CREATE TABLE sandbox_task_input (
    task_id UUID NOT NULL REFERENCES sandbox_task(id),
    file_id UUID NOT NULL REFERENCES file_asset(id),
    input_name VARCHAR(180) NOT NULL,
    PRIMARY KEY(task_id,file_id),
    UNIQUE(task_id,input_name)
);

CREATE TABLE sandbox_execution (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE REFERENCES sandbox_task(id),
    attempt INTEGER NOT NULL DEFAULT 1,
    container_id VARCHAR(128),
    image_id VARCHAR(128),
    exit_code INTEGER,
    oom_killed BOOLEAN NOT NULL DEFAULT FALSE,
    stdout TEXT NOT NULL DEFAULT '',
    stderr TEXT NOT NULL DEFAULT '',
    stdout_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    stderr_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    resource_usage JSONB NOT NULL DEFAULT '{}',
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE task_artifact (
    file_id UUID PRIMARY KEY REFERENCES file_asset(id),
    task_id UUID NOT NULL REFERENCES sandbox_task(id),
    execution_id UUID NOT NULL REFERENCES sandbox_execution(id),
    step_key VARCHAR(128) NOT NULL,
    output_id UUID NOT NULL,
    UNIQUE(execution_id,output_id)
);
CREATE INDEX idx_task_artifact_task ON task_artifact(task_id);
