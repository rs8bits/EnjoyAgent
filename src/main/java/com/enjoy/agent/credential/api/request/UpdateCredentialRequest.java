package com.enjoy.agent.credential.api.request;

import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新凭证请求。
 */
@Schema(name = "UpdateCredentialRequest", description = "更新用户凭证请求")
public record UpdateCredentialRequest(
        @Schema(description = "凭证名称，同一用户内必须唯一", example = "我的 OpenAI Key")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "新的 API Key，不传或传空表示不替换原密钥", example = "sk-yyyyy")
        @Size(max = 4096)
        String secret,

        @Schema(description = "OpenAI 兼容协议基础地址，不传则沿用当前值", example = "https://dashscope.aliyuncs.com/compatible-mode")
        @Size(max = 255)
        String baseUrl,

        @Schema(description = "备注说明", example = "主力模型调用密钥")
        @Size(max = 512)
        String description,

        @Schema(description = "凭证状态", example = "ACTIVE")
        CredentialStatus status
) {
}
