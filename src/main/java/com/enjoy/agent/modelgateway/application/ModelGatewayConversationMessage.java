package com.enjoy.agent.modelgateway.application;

import java.util.List;

/**
 * 模型调用时使用的会话消息。
 */
public record ModelGatewayConversationMessage(
        String role,
        String content,
        String toolCallId,
        List<ModelGatewayToolCall> toolCalls
) {

    public static ModelGatewayConversationMessage user(String content) {
        return new ModelGatewayConversationMessage("user", content, null, List.of());
    }

    public static ModelGatewayConversationMessage assistant(String content) {
        return new ModelGatewayConversationMessage("assistant", content, null, List.of());
    }

    public static ModelGatewayConversationMessage assistantToolCalls(List<ModelGatewayToolCall> toolCalls) {
        return new ModelGatewayConversationMessage("assistant", null, null, toolCalls);
    }

    public static ModelGatewayConversationMessage tool(String toolCallId, String content) {
        return new ModelGatewayConversationMessage("tool", content, toolCallId, List.of());
    }
}
