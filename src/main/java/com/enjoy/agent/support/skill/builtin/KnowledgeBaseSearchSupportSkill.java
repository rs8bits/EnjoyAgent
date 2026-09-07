package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseRepository;
import com.enjoy.agent.knowledge.infrastructure.persistence.KnowledgeBaseSummaryProjection;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRequest;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 查询当前租户知识库元数据，不读取原始文档内容或 embedding 数据。 */
@Component
public class KnowledgeBaseSearchSupportSkill implements SupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "knowledge_base.search", "1.0.0", "查询知识库",
            "按当前认证租户查询知识库安全摘要",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\"},\"hasMore\":{\"type\":\"boolean\"}}}",
            SupportSkillRisk.READ, "TENANT_MEMBER", false, 2_000
    );

    private final KnowledgeBaseRepository repository;
    private final SupportSkillPayloadWriter payloadWriter;

    public KnowledgeBaseSearchSupportSkill(
            KnowledgeBaseRepository repository,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.repository = repository;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() {
        return MANIFEST;
    }

    @Override
    @Transactional(readOnly = true)
    public SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request) {
        List<Map<String, Object>> items;
        boolean hasMore = false;
        if (request.resourceId() != null) {
            items = repository.findSupportSummaryByIdAndTenantId(
                            request.resourceId(), context.tenantId())
                    .map(this::project)
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            Slice<KnowledgeBaseSummaryProjection> page = repository.findSupportSummariesByTenantId(
                    context.tenantId(),
                    PageRequest.of(0, context.pageSize())
            );
            items = page.getContent().stream().map(this::project).toList();
            hasMore = page.hasNext();
        }
        return new SupportSkillResult(
                payloadWriter.write(Map.of("items", items, "hasMore", hasMore)),
                "已查询到 " + items.size() + " 个当前工作区知识库"
        );
    }

    private Map<String, Object> project(KnowledgeBaseSummaryProjection knowledgeBase) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", knowledgeBase.id());
        item.put("name", payloadWriter.safeText(knowledgeBase.name(), 128));
        item.put("description", payloadWriter.safeText(knowledgeBase.description(), 160));
        item.put("enabled", knowledgeBase.enabled());
        item.put("updatedAt", knowledgeBase.updatedAt());
        return item;
    }
}
