package com.enjoy.agent.support.domain.enums;

/**
 * 客服写操作确认状态。
 *
 * <p>状态故意保持精简：业务执行和状态更新在同一个本地数据库事务中完成，因此不需要
 * 暴露一个长期的 EXECUTING 中间态。若事务失败会整体回滚，用户可以在有效期内重试。</p>
 */
public enum SupportActionConfirmationStatus {
    PENDING,
    EXECUTED,
    CANCELLED,
    EXPIRED
}
