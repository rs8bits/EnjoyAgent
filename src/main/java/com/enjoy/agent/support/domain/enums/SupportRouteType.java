package com.enjoy.agent.support.domain.enums;

/**
 * 回答路径。该字段只描述系统采用了哪条公开路径，不保存模型的内部推理过程。
 */
public enum SupportRouteType {
    /** 无需模型的确定性问候或固定 FAQ。 */
    RULE,
    /** 由模型选择最小 Skill 集合；写操作在这里也只能生成待确认提案。 */
    SKILL,
    /** 不需要业务 Skill，直接由客服模型回答。 */
    LLM
}
