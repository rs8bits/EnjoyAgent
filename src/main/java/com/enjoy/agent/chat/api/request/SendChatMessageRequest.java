package com.enjoy.agent.chat.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发送聊天消息请求。
 */
@Schema(name = "SendChatMessageRequest", description = "向会话发送一条用户消息")
public record SendChatMessageRequest(
        @Schema(description = "用户输入内容", example = "请帮我总结一下这款产品的核心卖点。")
        @NotBlank
        @Size(max = 20000)
        String content
) {
}
