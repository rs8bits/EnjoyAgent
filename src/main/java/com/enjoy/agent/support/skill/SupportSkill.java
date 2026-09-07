package com.enjoy.agent.support.skill;

/** 平台内置、强类型、可审计的客服 Skill。 */
public interface SupportSkill {
    SupportSkillManifest manifest();

    SupportSkillResult execute(SupportSkillExecutionContext context, SupportSkillRequest request);
}
