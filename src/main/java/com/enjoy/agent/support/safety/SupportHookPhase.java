package com.enjoy.agent.support.safety;

/**
 * 客服安全 Hook 的固定执行点。
 *
 * <p>把检查点做成枚举而非在编排器里散落 if/else，后续接入内容审核服务、DLP
 * 或企业策略引擎时，只需新增 Hook，无需改写主流程。</p>
 */
public enum SupportHookPhase {
    USER_INPUT,
    BEFORE_CONTEXT,
    BEFORE_SKILL,
    AFTER_SKILL_RAW,
    BEFORE_MODEL_VISIBLE,
    AFTER_MODEL,
    BEFORE_ARCHIVE
}
