ALTER TABLE agent
    ADD COLUMN workflow_id BIGINT,
    ADD CONSTRAINT fk_agent_workflow FOREIGN KEY (workflow_id) REFERENCES workflow (id);

CREATE INDEX idx_agent_workflow_id ON agent (workflow_id);
