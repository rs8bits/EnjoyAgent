package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.agent.application.AgentApplicationService;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.agent.infrastructure.persistence.AgentSummaryProjection;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.ConfirmableSupportSkill;
import com.enjoy.agent.support.skill.SupportActionPreview;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 删除当前租户 Agent 的确认型 Skill；真正删除仍复用 Agent 领域服务。 */
@Component
public class AgentDeleteSupportSkill implements ConfirmableSupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "agent.delete", "1.0.0", "删除 Agent",
            "永久删除当前租户中的一个 Agent；执行前必须由工作区拥有者明确确认",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"resourceId\"],\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"deleted\":{\"type\":\"boolean\"},"
                    + "\"resourceId\":{\"type\":\"integer\"}}}",
            SupportSkillRisk.DESTRUCTIVE, "OWNER", true, 800
    );

    private final AgentRepository repository;
    private final AgentApplicationService applicationService;
    private final SupportSkillPayloadWriter payloadWriter;

    public AgentDeleteSupportSkill(
            AgentRepository repository,
            AgentApplicationService applicationService,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.repository = repository;
        this.applicationService = applicationService;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() { return MANIFEST; }

    @Override
    @Transactional(readOnly = true)
    public SupportActionPreview preview(
            SupportSkillExecutionContext context,
            Map<String, Object> requestedArguments
    ) {
        Long resourceId = requireResourceId(requestedArguments);
        AgentSummaryProjection agent = requireAgent(context.tenantId(), resourceId);
        String name = payloadWriter.safeText(agent.name(), 128);
        return new SupportActionPreview(
                Map.of("resourceId", resourceId),
                "确认删除 Agent",
                "将永久删除当前工作区 Agent「" + name + "」(#" + resourceId
                        + ")，并删除它的 MCP 工具绑定。此操作不可撤销。",
                agent.updatedAt()
        );
    }

    @Override
    public SupportSkillResult executeConfirmed(
            SupportSkillExecutionContext context,
            Map<String, Object> normalizedArguments,
            SupportActionPreview originalPreview
    ) {
        Long resourceId = requireResourceId(normalizedArguments);
        AgentSummaryProjection current = requireAgent(context.tenantId(), resourceId);
        requireUnchanged(current.updatedAt(), originalPreview.targetUpdatedAt());
        String name = payloadWriter.safeText(current.name(), 128);
        // 领域服务继续负责租户归属校验、缓存失效以及关联绑定的清理。
        applicationService.deleteAgent(resourceId);
        return new SupportSkillResult(
                payloadWriter.write(Map.of("deleted", true, "resourceId", resourceId, "name", name)),
                "已删除 Agent「" + name + "」(#" + resourceId + ")"
        );
    }

    private AgentSummaryProjection requireAgent(Long tenantId, Long resourceId) {
        return repository.findSupportSummaryByIdAndTenantId(resourceId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "AGENT_NOT_FOUND", "Agent 不存在或不属于当前工作区", HttpStatus.NOT_FOUND));
    }

    private Long requireResourceId(Map<String, Object> arguments) {
        try {
            Object value = arguments == null ? null : arguments.get("resourceId");
            Long id = value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (RuntimeException ex) {
            throw new ApiException("SUPPORT_SKILL_ARGUMENT_INVALID", "请选择要删除的 Agent", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireUnchanged(Instant current, Instant expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ApiException(
                    "SUPPORT_ACTION_TARGET_CHANGED",
                    "Agent 在确认期间已经发生变化，请重新发起删除",
                    HttpStatus.CONFLICT
            );
        }
    }
}
