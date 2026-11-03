ALTER TABLE sandbox_task DROP CONSTRAINT sandbox_task_operation_check;
ALTER TABLE sandbox_task ADD CONSTRAINT sandbox_task_operation_check CHECK(operation IN
 ('DEMO','PYTHON','SQL','PDF_EXTRACT','PDF_SPLIT','PDF_MERGE','PDF_RENDER','OFFICE_CONVERT','TABLE_INSPECT','TABLE_ANALYZE'));
ALTER TABLE sandbox_task ADD COLUMN arguments_json JSONB NOT NULL DEFAULT '{}',
 ADD COLUMN chat_message_id BIGINT REFERENCES chat_message(id) ON DELETE SET NULL,
 ADD COLUMN workflow_execution_id BIGINT REFERENCES workflow_execution(id) ON DELETE SET NULL;
CREATE INDEX idx_sandbox_task_chat_message ON sandbox_task(chat_message_id);
CREATE INDEX idx_sandbox_task_workflow ON sandbox_task(workflow_execution_id);
ALTER TABLE sandbox_task_input ADD COLUMN input_order INTEGER NOT NULL DEFAULT 0;

CREATE TABLE agent_sandbox_capability (
 agent_id BIGINT PRIMARY KEY REFERENCES agent(id) ON DELETE CASCADE,
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE chat_message_file (
 message_id BIGINT NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
 file_id UUID NOT NULL REFERENCES file_asset(id),
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('INPUT','ARTIFACT')),
 PRIMARY KEY(message_id,file_id)
);
