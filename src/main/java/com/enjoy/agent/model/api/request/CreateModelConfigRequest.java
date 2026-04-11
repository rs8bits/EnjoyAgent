package com.enjoy.agent.model.api.request;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 创建模型配置请求。
 */
@Schema(name = "CreateModelConfigRequest", description = "创建模型配置请求")
public record CreateModelConfigRequest(
        @Schema(description = "配置名称，同一租户内必须唯一", example = "默认对话模型")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "模型提供方", example = "OPENAI")
        @NotNull
        CredentialProvider provider,

        @Schema(description = "模型类型", example = "CHAT")
        @NotNull
        ModelType modelType,

        @Schema(description = "模型名称", example = "gpt-4o-mini")
        @NotBlank
        @Size(max = 128)
        String modelName,

        @Schema(description = "密钥来源，USER 表示使用当前用户凭证，PLATFORM 表示使用平台托管密钥", example = "USER")
        @NotNull
        ModelCredentialSource credentialSource,

        @Schema(description = "绑定的用户凭证 ID，仅当 credentialSource=USER 时必填", example = "1")
        Long credentialId,

        @Schema(description = "采样温度，仅对对话模型有意义", example = "0.2")
        @DecimalMin(value = "0.0")
        @DecimalMax(value = "2.0")
        BigDecimal temperature,

        @Schema(description = "最大输出 token 数", example = "2048")
        @Positive
        Integer maxTokens,

        @Schema(description = "是否启用", example = "true")
        Boolean enabled
) {
}
