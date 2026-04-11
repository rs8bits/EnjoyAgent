ALTER TABLE model_config
    ADD COLUMN credential_source VARCHAR(32);

UPDATE model_config
SET credential_source = CASE
                            WHEN credential_id IS NULL THEN 'PLATFORM'
                            ELSE 'USER'
    END
WHERE credential_source IS NULL;

ALTER TABLE model_config
    ALTER COLUMN credential_source SET NOT NULL;

CREATE INDEX idx_model_config_credential_source ON model_config (credential_source);
