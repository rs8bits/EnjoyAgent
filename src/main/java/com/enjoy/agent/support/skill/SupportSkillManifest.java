package com.enjoy.agent.support.skill;

/**
 * 可注入模型的 Skill 元数据。
 *
 * <p>确定性规则只处理简单寒暄，其余意图由模型依据这里的描述和 JSON Schema
 * 选择 Skill。风险、权限和确认要求仍由服务端强制执行，不能信任模型判断。</p>
 */
public record SupportSkillManifest(
        String name,
        String version,
        String displayName,
        String description,
        String inputSchemaJson,
        String outputSchemaJson,
        SupportSkillRisk risk,
        String requiredPermission,
        boolean confirmationRequired,
        int maxModelResultChars
) {
}
