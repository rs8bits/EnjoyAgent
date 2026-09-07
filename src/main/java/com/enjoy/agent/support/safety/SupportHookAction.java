package com.enjoy.agent.support.safety;

/** Hook 的确定性处理动作；确认型 Skill 的正常路径由确认状态机负责。 */
public enum SupportHookAction {
    ALLOW,
    REDACT,
    BLOCK,
    REQUIRE_CONFIRMATION
}
