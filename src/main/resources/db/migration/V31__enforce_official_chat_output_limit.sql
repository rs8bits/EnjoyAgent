UPDATE official_model_config
SET max_tokens = 4096
WHERE model_type = 'CHAT'
  AND max_tokens IS NULL;

ALTER TABLE official_model_config
    ADD CONSTRAINT ck_official_chat_max_tokens
        CHECK (model_type <> 'CHAT' OR (max_tokens IS NOT NULL AND max_tokens BETWEEN 1 AND 131072));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM user_wallet
        WHERE balance < 0
           OR reserved_amount < 0
           OR reserved_amount > balance
    ) THEN
        RAISE EXCEPTION
            'Cannot validate wallet credit invariant: remediate negative/over-reserved legacy wallets first';
    END IF;
END
$$;

ALTER TABLE user_wallet
    VALIDATE CONSTRAINT ck_user_wallet_reserved_within_balance;
