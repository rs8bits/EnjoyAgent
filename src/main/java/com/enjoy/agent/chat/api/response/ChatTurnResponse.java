package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一次聊天轮次的返回对象。
 */
@Schema(name = "ChatTurnResponse", description = "一次用户提问和模型回复的结果")
public record ChatTurnResponse(
        @Schema(description = "会话 ID")
        Long sessionId,

        @Schema(description = "本次用户消息")
        ChatMessageResponse userMessage,

        @Schema(description = "本次模型回复")
        ChatMessageResponse assistantMessage,

        @Schema(description = "本次调用的模型提供方", example = "OPENAI")
        String provider,

        @Schema(description = "本次调用的模型名称", example = "gpt-4o-mini")
        String modelName,

        @Schema(description = "本次调用使用的密钥来源", example = "USER")
        String credentialSource,

        @Schema(description = "本次调用使用的凭证 ID，若走平台密钥则为空")
        Long credentialId,

        @Schema(description = "本次模型调用耗时，单位毫秒")
        Long latencyMs,

        @Schema(description = "本次请求消耗的提示词 tokens")
        Integer promptTokens,

        @Schema(description = "本次回复消耗的 completion tokens")
        Integer completionTokens,

        @Schema(description = "本次总 tokens")
        Integer totalTokens,

        @Schema(description = "本次知识检索调试信息")
        KnowledgeRetrievalDebugResponse retrievalDebug
) {
}
