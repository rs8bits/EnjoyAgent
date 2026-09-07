package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.knowledge.application.KnowledgeBaseApplicationService;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseSummaryProjection;
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

/** 删除空闲知识库的确认型 Skill。文档和 Agent 引用限制由原领域服务统一维护。 */
@Component
public class KnowledgeBaseDeleteSupportSkill implements ConfirmableSupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "knowledge_base.delete", "1.0.0", "删除知识库",
            "永久删除当前租户中的空知识库；执行前必须由工作区拥有者明确确认",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},"
                    + "\"required\":[\"resourceId\"],\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"deleted\":{\"type\":\"boolean\"},"
                    + "\"resourceId\":{\"type\":\"integer\"}}}",
            SupportSkillRisk.DESTRUCTIVE, "OWNER", true, 800
    );

    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseApplicationService applicationService;
    private final SupportSkillPayloadWriter payloadWriter;

    public KnowledgeBaseDeleteSupportSkill(
            KnowledgeBaseRepository repository,
            KnowledgeBaseApplicationService applicationService,
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
        KnowledgeBaseSummaryProjection knowledgeBase = requireKnowledgeBase(context.tenantId(), resourceId);
        String name = payloadWriter.safeText(knowledgeBase.name(), 128);
        return new SupportActionPreview(
                Map.of("resourceId", resourceId),
                "确认删除知识库",
                "将永久删除当前工作区知识库「" + name + "」(#" + resourceId
                        + ")。只有不含文档且未被 Agent 使用的知识库才能删除。",
                knowledgeBase.updatedAt()
        );
    }

    @Override
    public SupportSkillResult executeConfirmed(
            SupportSkillExecutionContext context,
            Map<String, Object> normalizedArguments,
            SupportActionPreview originalPreview
    ) {
        Long resourceId = requireResourceId(normalizedArguments);
        KnowledgeBaseSummaryProjection current = requireKnowledgeBase(context.tenantId(), resourceId);
        requireUnchanged(current.updatedAt(), originalPreview.targetUpdatedAt());
        String name = payloadWriter.safeText(current.name(), 128);
        applicationService.deleteKnowledgeBase(resourceId);
        return new SupportSkillResult(
                payloadWriter.write(Map.of("deleted", true, "resourceId", resourceId, "name", name)),
                "已删除知识库「" + name + "」(#" + resourceId + ")"
        );
    }

    private KnowledgeBaseSummaryProjection requireKnowledgeBase(Long tenantId, Long resourceId) {
        return repository.findSupportSummaryByIdAndTenantId(resourceId, tenantId)
                .orElseThrow(() -> new ApiException(
                        "KNOWLEDGE_BASE_NOT_FOUND",
                        "知识库不存在或不属于当前工作区",
                        HttpStatus.NOT_FOUND
                ));
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
            throw new ApiException(
                    "SUPPORT_SKILL_ARGUMENT_INVALID",
                    "请选择要删除的知识库",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private void requireUnchanged(Instant current, Instant expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ApiException(
                    "SUPPORT_ACTION_TARGET_CHANGED",
                    "知识库在确认期间已经发生变化，请重新发起删除",
                    HttpStatus.CONFLICT
            );
        }
    }
}
