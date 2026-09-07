package com.enjoy.agent.support.safety;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.UUID;

/**
 * Hook 使用的可信执行身份。ID 和角色都来自认证快照与服务端持久化对象，绝不从
 * 模型参数或浏览器 JSON 中读取 tenantId、userId 或权限。
 */
public record SupportHookContext(
        Long tenantId,
        Long userId,
        Long conversationId,
        UUID turnId,
        TenantMemberRole tenantRole,
        SystemRole systemRole
) {
}
