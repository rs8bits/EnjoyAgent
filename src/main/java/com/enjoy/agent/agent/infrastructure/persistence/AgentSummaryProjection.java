package com.enjoy.agent.agent.infrastructure.persistence;

import java.time.Instant;

/**
 * Agent 的最小只读投影。
 *
 * <p>客服查询使用构造器投影后，SQL 不会选择 system_prompt、模型配置或关联凭证，
 * 从数据访问源头贯彻最小权限，而不是加载完整实体后再寄希望于序列化时忽略。</p>
 */
public record AgentSummaryProjection(
        Long id,
        String name,
        String description,
        boolean enabled,
        String knowledgeBaseName,
        Instant updatedAt
) {
}
