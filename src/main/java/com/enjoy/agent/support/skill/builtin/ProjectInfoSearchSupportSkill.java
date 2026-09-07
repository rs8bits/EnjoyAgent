package com.enjoy.agent.support.skill.builtin;

import com.enjoy.agent.support.application.SupportProperties;
import com.enjoy.agent.support.skill.SupportSkill;
import com.enjoy.agent.support.skill.SupportSkillExecutionContext;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillPayloadWriter;
import com.enjoy.agent.support.skill.SupportSkillRequest;
import com.enjoy.agent.support.skill.SupportSkillResult;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 项目介绍 Skill。
 *
 * <p>资料使用受版本控制的白名单片段，不把整个 README 或后台配置塞入上下文。
 * 简单“项目是什么”由规则直接回答；复杂项目问题才会注入这些片段。</p>
 */
@Component
public class ProjectInfoSearchSupportSkill implements SupportSkill {

    private final SupportProperties properties;
    private final SupportSkillPayloadWriter payloadWriter;

    public ProjectInfoSearchSupportSkill(
            SupportProperties properties,
            SupportSkillPayloadWriter payloadWriter
    ) {
        this.properties = properties;
        this.payloadWriter = payloadWriter;
    }

    @Override
    public SupportSkillManifest manifest() {
        return new SupportSkillManifest(
                "project_info.search", "1.0.0", "查询项目资料",
                "返回 EnjoyAgent 的版本化公开能力说明",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                "{\"type\":\"object\",\"properties\":{\"project\":{\"type\":\"string\"},\"snippets\":{\"type\":\"array\"}}}",
                SupportSkillRisk.READ, "AUTHENTICATED", false, 2_000
        );
    }

    @Override
    public SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request) {
        List<Map<String, String>> snippets = List.of(
                Map.of(
                        "topic", "Agent 与模型",
                        "content", "用户可以在租户工作区配置 Agent，并绑定用户模型或平台官方模型。"
                ),
                Map.of(
                        "topic", "知识与自动化",
                        "content", "平台提供知识库、文档导入、检索增强、工作流和 MCP 工具集成。"
                ),
                Map.of(
                        "topic", "账户与计费",
                        "content", "凭证归当前用户所有；Agent 和知识库按当前租户隔离；充值采用提交申请后由管理员人工审核的流程。"
                ),
                Map.of(
                        "topic", "常用入口",
                        "content", "Agent 在 /app/agents 管理，知识库在 /app/knowledge 管理，API Key 在 /app/credentials 管理，充值申请与余额在 /app/wallet 查看。部分租户资产操作仅工作区拥有者可用。"
                )
        );
        return new SupportSkillResult(
                payloadWriter.write(Map.of(
                        "project", properties.getProjectName(),
                        "contentVersion", "2026-09-phase-1",
                        "snippets", snippets
                )),
                "已加载 " + snippets.size() + " 条版本化项目资料"
        );
    }
}
