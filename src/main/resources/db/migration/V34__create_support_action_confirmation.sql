-- 阶段二把所有会产生业务写入的客服 Skill 都拆成“提案 -> 用户确认 -> 执行”三步。
-- 这张表保存不可变的规范化参数和哈希，确认接口只能执行这里已经展示给用户的内容，
-- 不能接受浏览器在确认时重新提交一份可被篡改的工具参数。
CREATE TABLE support_action_confirmation
(
    id                    UUID          PRIMARY KEY,
    conversation_id       BIGINT        NOT NULL,
    source_turn_id        UUID          NOT NULL,
    execution_turn_id     UUID,
    tenant_id             BIGINT        NOT NULL,
    user_id               BIGINT        NOT NULL,
    skill_name            VARCHAR(128)  NOT NULL,
    skill_version         VARCHAR(32)   NOT NULL,
    arguments_payload     TEXT          NOT NULL,
    arguments_hash        VARCHAR(64)   NOT NULL,
    title                 VARCHAR(160)  NOT NULL,
    operation_summary     VARCHAR(1000) NOT NULL,
    -- 删除类操作在展示确认卡片时记录资源更新时间；确认时会重新读取资源，避免
    -- 用户确认期间目标已经被修改却仍按旧信息执行。
    target_updated_at     TIMESTAMPTZ,
    status                VARCHAR(16)   NOT NULL,
    expires_at            TIMESTAMPTZ   NOT NULL,
    confirmed_at          TIMESTAMPTZ,
    executed_at           TIMESTAMPTZ,
    cancelled_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_support_confirmation_conversation FOREIGN KEY (conversation_id)
        REFERENCES support_conversation (id),
    CONSTRAINT fk_support_confirmation_source_turn FOREIGN KEY (source_turn_id)
        REFERENCES support_turn (id),
    CONSTRAINT fk_support_confirmation_execution_turn FOREIGN KEY (execution_turn_id)
        REFERENCES support_turn (id),
    CONSTRAINT fk_support_confirmation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_support_confirmation_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uk_support_confirmation_source_call
        UNIQUE (source_turn_id, skill_name, arguments_hash),
    CONSTRAINT uk_support_confirmation_execution_turn UNIQUE (execution_turn_id),
    CONSTRAINT ck_support_confirmation_status
        CHECK (status IN ('PENDING', 'EXECUTED', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_support_confirmation_scope_created
    ON support_action_confirmation (tenant_id, user_id, created_at DESC);

CREATE INDEX idx_support_confirmation_pending_expiry
    ON support_action_confirmation (expires_at)
    WHERE status = 'PENDING';
