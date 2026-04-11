package com.enjoy.agent.credential.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 凭证返回对象。
 */
@Schema(name = "CredentialResponse", description = "凭证信息返回对象，永远不会暴露真实密钥")
public record CredentialResponse(
        @Schema(description = "凭证 ID", example = "1")
        Long id,

        @Schema(description = "凭证名称", example = "我的 OpenAI Key")
        String name,

        @Schema(description = "提供方", example = "OPENAI")
        String provider,

        @Schema(description = "凭证类型", example = "API_KEY")
        String credentialType,

        @Schema(description = "脱敏后的密钥", example = "sk-1****abcd")
        String secretMasked,

        @Schema(description = "OpenAI 兼容协议基础地址", example = "https://dashscope.aliyuncs.com/compatible-mode")
        String baseUrl,

        @Schema(description = "备注说明", example = "给个人测试 Agent 使用")
        String description,

        @Schema(description = "凭证状态", example = "ACTIVE")
        String status,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
