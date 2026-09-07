package com.enjoy.agent.support.application;

/** 已通过工具输出 Hook、允许进入模型上下文的最小 Skill 结果。 */
public record SupportSkillContextItem(String skillName, String modelPayload) {
}
