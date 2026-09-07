package com.enjoy.agent.support.application;

/**
 * 一个经过服务端校验的客服工具调用。
 *
 * @param callId 模型生成的调用 ID，后续组织工具消息时用于关联请求和结果
 * @param skillName 已通过 {@code SupportSkillRegistry} 白名单校验的 Skill 名称
 * @param argumentsJson 经过 JSON 解析和稳定字段排序后的对象参数
 */
public record SupportPlannedToolCall(
        String callId,
        String skillName,
        String argumentsJson
) {
}
