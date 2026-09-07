package com.enjoy.agent.support.domain.enums;

/**
 * 客服消息角色。
 *
 * <p>聊天时间线只保存用户输入和最终助手回复。Skill 的请求、输出和状态拥有独立的
 * {@code support_skill_call} 审计表，避免把工具协议细节伪装成普通聊天消息。</p>
 */
public enum SupportMessageRole {
    USER,
    ASSISTANT
}
