package com.enjoy.agent.model.api.request;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "UpdateOfficialModelCredentialRequest", description = "更新官方模型凭证请求")
public record UpdateOfficialModelCredentialRequest(
        @Schema(description = "凭证名称", example = "百炼官方 Key")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "提供方", example = "OPENAI")
        @NotNull
        CredentialProvider provider,

        @Schema(description = "基础地址", example = "https://dashscope.aliyuncs.com/compatible-mode")
        @NotBlank
        @Size(max = 255)
        String baseUrl,

        @Schema(description = "新的 API Key 明文，不传表示不修改")
        @Size(max = 2048)
        String secretPlaintext,

        @Schema(description = "是否启用", example = "true")
        @NotNull
        Boolean enabled
) {
}
