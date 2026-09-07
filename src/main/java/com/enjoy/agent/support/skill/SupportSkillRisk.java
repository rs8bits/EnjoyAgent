package com.enjoy.agent.support.skill;

/** Skill 风险等级；除 READ 外的能力必须实现确认型 Skill 契约。 */
public enum SupportSkillRisk {
    READ,
    WRITE,
    FINANCIAL,
    DESTRUCTIVE
}
