package com.enjoy.agent.chat.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 发送聊天消息请求。
 */
@Schema(name = "SendChatMessageRequest", description = "向会话发送一条用户消息")
public record SendChatMessageRequest(
        @Schema(description = "用户输入内容", example = "请帮我总结一下这款产品的核心卖点。")
        @NotBlank
        @Size(max = 20000)
        String content,

        @Schema(description = "本次消息允许使用的短期 HIGH 风险 MCP 工具审批令牌")
        @Size(max = 20)
        List<@Size(max = 2048) String> toolApprovalTokens
) {
    public SendChatMessageRequest(String content) {
        this(content, List.of());
    }
}
