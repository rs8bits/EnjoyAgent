ALTER TABLE app_user
    ADD COLUMN system_role VARCHAR(32);

UPDATE app_user
SET system_role = 'USER'
WHERE system_role IS NULL;

ALTER TABLE app_user
    ALTER COLUMN system_role SET NOT NULL;

DROP TABLE IF EXISTS billing_transaction;
DROP TABLE IF EXISTS billing_account;
