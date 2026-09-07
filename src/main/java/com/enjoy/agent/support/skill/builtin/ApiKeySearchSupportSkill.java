package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.credential.infrastructure.persistence.CredentialMetadataProjection;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
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

/**
 * 查询当前用户的上游模型 API Key 元数据。
 *
 * <p>注意：凭证属于用户而不是租户；Skill 必须按 userId 过滤。代码既不读取
 * secretCiphertext，也不复用可能由旧算法生成的历史掩码，只报告是否已配置。</p>
 */
@Component
public class ApiKeySearchSupportSkill implements SupportSkill {

    private static final SupportSkillManifest MANIFEST = new SupportSkillManifest(
            "api_key.search", "1.0.0", "查询 API Key",
            "查询当前用户的上游模型凭证元数据，永不返回明文密钥",
            "{\"type\":\"object\",\"properties\":{\"resourceId\":{\"type\":\"integer\"}},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\"},\"hasMore\":{\"type\":\"boolean\"}}}",
            SupportSkillRisk.READ, "CURRENT_USER", false, 2_000
    );

    private final CredentialRepository repository;
    private final SupportSkillPayloadWriter payloadWriter;

    public ApiKeySearchSupportSkill(CredentialRepository repository, SupportSkillPayloadWriter payloadWriter) {
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
            items = repository.findSupportMetadataByIdAndUserId(
                            request.resourceId(), context.userId())
                    .map(this::project)
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            Slice<CredentialMetadataProjection> page = repository.findSupportMetadataByUserId(
                    context.userId(),
                    PageRequest.of(0, context.pageSize())
            );
            items = page.getContent().stream().map(this::project).toList();
            hasMore = page.hasNext();
        }
        return new SupportSkillResult(
                payloadWriter.write(Map.of("items", items, "hasMore", hasMore)),
                "已查询到 " + items.size() + " 个当前用户凭证；密钥明文不会进入客服会话"
        );
    }

    private Map<String, Object> project(CredentialMetadataProjection credential) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", credential.id());
        item.put("name", payloadWriter.safeText(credential.name(), 128));
        item.put("provider", credential.provider().name());
        item.put("type", credential.credentialType().name());
        // 历史 secretMasked 可能由旧算法产生，无法仅从掩码判断泄漏比例。客服端不
        // 复用该字段，只确认“已配置”；用户需要识别时可依据凭证名称和 provider。
        item.put("masked", "已配置（明文不可查看）");
        item.put("status", credential.status().name());
        item.put("updatedAt", credential.updatedAt());
        return item;
    }
}
