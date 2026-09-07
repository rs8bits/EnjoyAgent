package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** 确认或取消操作后的页面响应；执行成功时同时返回写入长期会话的两条正式消息。 */
public record SupportActionExecutionResponse(
        UUID confirmationId,
        String status,
        SupportMessageResponse userMessage,
        SupportMessageResponse assistantMessage,
        String message
) {
}
