package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.agent.infrastructure.persistence.AgentSummaryProjection;
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

/** 查询当前租户可见的 Agent；不会把 system prompt 或模型凭证送给客服模型。 */
@Component
public class AgentSearchSupportSkill implements SupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "agent.search",
            "1.0.0",
            "查询 Agent",
            "按当前认证租户查询 Agent 的安全摘要",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\"},\"hasMore\":{\"type\":\"boolean\"}}}",
            SupportSkillRisk.READ,
            "TENANT_MEMBER",
            false,
            2_000
    );

    private final AgentRepository agentRepository;
    private final SupportSkillPayloadWriter payloadWriter;

    public AgentSearchSupportSkill(AgentRepository agentRepository, SupportSkillPayloadWriter payloadWriter) {
        this.agentRepository = agentRepository;
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
            items = agentRepository.findSupportSummaryByIdAndTenantId(
                            request.resourceId(), context.tenantId())
                    .map(this::project)
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            Slice<AgentSummaryProjection> page = agentRepository.findSupportSummariesByTenantId(
                    context.tenantId(),
                    PageRequest.of(0, context.pageSize())
            );
            items = page.getContent().stream().map(this::project).toList();
            hasMore = page.hasNext();
        }
        String payload = payloadWriter.write(Map.of("items", items, "hasMore", hasMore));
        return new SupportSkillResult(payload, "已查询到 " + items.size() + " 个当前工作区 Agent");
    }

    private Map<String, Object> project(AgentSummaryProjection agent) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", agent.id());
        item.put("name", payloadWriter.safeText(agent.name(), 128));
        item.put("description", payloadWriter.safeText(agent.description(), 160));
        item.put("enabled", agent.enabled());
        item.put("knowledgeBase", payloadWriter.safeText(agent.knowledgeBaseName(), 128));
        item.put("updatedAt", agent.updatedAt());
        return item;
    }
}
