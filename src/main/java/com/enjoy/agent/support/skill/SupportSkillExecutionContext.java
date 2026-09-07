package com.enjoy.agent.support.skill;

import java.util.UUID;

/**
 * Skill 的可信服务端上下文。实现类必须使用这里的 tenantId/userId 做仓储过滤，
 * 不得接受模型在参数中提供同名字段。
 */
public record SupportSkillExecutionContext(
        Long tenantId,
        Long userId,
        Long conversationId,
        UUID turnId,
        int pageSize
) {
}
