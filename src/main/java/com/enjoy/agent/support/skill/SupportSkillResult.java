package com.enjoy.agent.support.skill;

/**
 * Skill 执行结果。
 *
 * @param rawPayload 结构化原始结果；仍须经过 AFTER_SKILL_RAW Hook
 * @param displaySummary 可展示给用户的简短状态文案；不得包含秘密
 */
public record SupportSkillResult(String rawPayload, String displaySummary) {
}
