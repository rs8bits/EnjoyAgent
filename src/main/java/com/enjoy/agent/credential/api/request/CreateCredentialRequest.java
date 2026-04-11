package com.enjoy.agent.credential.api.request;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建凭证请求。
 */
@Schema(name = "CreateCredentialRequest", description = "创建用户凭证请求")
public record CreateCredentialRequest(
        @Schema(description = "凭证名称，同一用户内必须唯一", example = "我的 OpenAI Key")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "凭证提供方", example = "OPENAI")
        @NotNull
        CredentialProvider provider,

        @Schema(description = "真实 API Key，只在创建或更新时传入，不会在响应中返回", example = "sk-xxxxx")
        @NotBlank
        @Size(max = 4096)
        String secret,

        @Schema(description = "OpenAI 兼容协议基础地址，百炼可填写 https://dashscope.aliyuncs.com/compatible-mode", example = "https://dashscope.aliyuncs.com/compatible-mode")
        @Size(max = 255)
        String baseUrl,

        @Schema(description = "备注说明", example = "给个人测试 Agent 使用")
        @Size(max = 512)
        String description
) {
}
