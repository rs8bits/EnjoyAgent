package com.enjoy.agent.support.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 客服提问请求。租户和用户身份永远由服务端认证上下文提供。 */
@Schema(name = "SendSupportMessageRequest", description = "发送给平台 AI 客服的消息")
public record SendSupportMessageRequest(
        @NotBlank
        @Size(max = 4000)
        @Schema(description = "用户问题，最多 4000 个 Java 字符", example = "帮我看看当前工作区有哪些 Agent")
        String content
) {
}
