package com.enjoy.agent.support.safety;

import com.enjoy.agent.support.skill.SupportSkillManifest;

/** 单个 Hook 的输入快照。payload 可能被前一个 Hook 脱敏后再传给下一个 Hook。 */
public record SupportHookRequest(
        SupportHookPhase phase,
        SupportHookContext context,
        SupportSkillManifest skill,
        String payload
) {
}
