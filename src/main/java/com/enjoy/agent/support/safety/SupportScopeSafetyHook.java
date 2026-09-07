package com.enjoy.agent.support.safety;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Skill 执行前的权限边界 Hook。
 *
 * <p>READ Skill 可以直接执行；写入类 Skill 必须在 Manifest 中声明确认，并实现独立
 * 的 ConfirmableSupportSkill。这个 Hook 负责身份和权限，确认服务负责校验一次性提案，
 * 两层都通过后才可能产生业务写入。</p>
 */
@Component
public class SupportScopeSafetyHook implements SupportSafetyHook {

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Set<SupportHookPhase> phases() {
        return Set.of(SupportHookPhase.BEFORE_SKILL);
    }

    @Override
    public SupportHookDecision evaluate(SupportHookRequest request) {
        SupportHookContext context = request.context();
        if (context == null || context.tenantId() == null || context.userId() == null
                || context.conversationId() == null || context.turnId() == null) {
            return SupportHookDecision.block(
                    "SUPPORT_SECURITY_CONTEXT_MISSING",
                    "当前客服安全上下文无效，请重新登录后重试",
                    HttpStatus.UNAUTHORIZED
            );
        }
        if (request.skill() == null) {
            return SupportHookDecision.block(
                    "SUPPORT_SKILL_RISK_NOT_ALLOWED",
                    "客服工具风险配置无效",
                    HttpStatus.FORBIDDEN
            );
        }
        if (request.skill().risk() != SupportSkillRisk.READ
                && !request.skill().confirmationRequired()) {
            return SupportHookDecision.block(
                    "SUPPORT_SKILL_CONFIRMATION_REQUIRED",
                    "该操作必须经过明确确认",
                    HttpStatus.FORBIDDEN
            );
        }
        if (!hasRequiredPermission(context, request.skill().requiredPermission())) {
            // 统一返回无权执行，不透露这个 Skill 在其他角色下是否可用。
            return SupportHookDecision.block(
                    "SUPPORT_SKILL_PERMISSION_DENIED",
                    "当前账号无权执行该客服查询",
                    HttpStatus.FORBIDDEN
            );
        }
        return SupportHookDecision.allow(request.payload());
    }

    private boolean hasRequiredPermission(SupportHookContext context, String permission) {
        if (permission == null) {
            return false;
        }
        return switch (permission) {
            case "AUTHENTICATED" -> context.tenantRole() != null && context.systemRole() != null;
            // CURRENT_USER 的资源归属仍由仓储的 userId 条件强制；这里确认执行身份
            // 完整，避免把客户端或模型参数误当成用户身份。
            case "CURRENT_USER", "TENANT_MEMBER" -> context.tenantRole() != null;
            case "OWNER" -> context.tenantRole() == TenantMemberRole.OWNER;
            case "ADMIN" -> context.systemRole() == SystemRole.ADMIN;
            default -> false;
        };
    }
}
