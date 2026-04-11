package com.enjoy.agent.chat.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建聊天会话请求。
 */
@Schema(name = "CreateChatSessionRequest", description = "创建聊天会话请求")
public record CreateChatSessionRequest(
        @Schema(description = "会话所属的 Agent ID", example = "1")
        @NotNull
        Long agentId,

        @Schema(description = "会话标题，留空时会自动生成默认标题", example = "产品答疑会话")
        @Size(max = 128)
        String title
) {
}
