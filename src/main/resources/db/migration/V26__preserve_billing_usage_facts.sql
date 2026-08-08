-- 计费事件是独立的财务快照：会话删除时可以删除 model_call_log，但不能删除已发生的用量事实。
ALTER TABLE billing_usage_event
    DROP CONSTRAINT IF EXISTS fk_billing_usage_event_model_call_log;

-- 即使消息重复投递或业务幂等判断失效，同一次模型调用也只能产生一条扣费流水。
CREATE UNIQUE INDEX uk_user_wallet_transaction_model_usage_call
    ON user_wallet_transaction (transaction_type, reference_type, reference_id)
    WHERE transaction_type = 'MODEL_USAGE_DEBIT'
      AND reference_type = 'MODEL_CALL_LOG'
      AND reference_id IS NOT NULL;
