ALTER TABLE workflow_node ADD COLUMN node_key VARCHAR(64);

UPDATE workflow_node
SET node_key = 'node_' || id
WHERE node_key IS NULL;

ALTER TABLE workflow_node ALTER COLUMN node_key SET NOT NULL;

ALTER TABLE workflow_node
    ADD CONSTRAINT uk_workflow_node_workflow_key UNIQUE (workflow_id, node_key);
