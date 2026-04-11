package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天流式增量事件。
 */
@Schema(name = "ChatStreamDeltaResponse", description = "聊天流式增量文本事件")
public record ChatStreamDeltaResponse(
        @Schema(description = "本次新增文本片段")
        String delta
) {
}
