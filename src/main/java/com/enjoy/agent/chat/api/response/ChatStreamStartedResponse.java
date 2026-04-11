package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天流式响应开始事件。
 */
@Schema(name = "ChatStreamStartedResponse", description = "聊天流式输出开始事件")
public record ChatStreamStartedResponse(
        @Schema(description = "会话 ID")
        Long sessionId,

        @Schema(description = "本次用户消息 ID")
        Long userMessageId,

        @Schema(description = "本次用户消息内容")
        String userMessageContent,

        @Schema(description = "本次调用的模型提供方", example = "OPENAI")
        String provider,

        @Schema(description = "本次调用的模型名称", example = "qwen-plus")
        String modelName,

        @Schema(description = "本次调用使用的密钥来源", example = "USER")
        String credentialSource,

        @Schema(description = "本次调用使用的凭证 ID，若走平台密钥则为空")
        Long credentialId,

        @Schema(description = "本次流式模式，STREAM 表示真正流式，SYNC_FALLBACK 表示工具模式下回退同步", example = "STREAM")
        String mode
) {
}
