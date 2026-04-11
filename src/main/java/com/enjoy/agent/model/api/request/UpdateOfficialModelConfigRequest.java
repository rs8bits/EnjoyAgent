package com.enjoy.agent.model.api.request;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(name = "UpdateOfficialModelConfigRequest", description = "更新官方模型配置请求")
public record UpdateOfficialModelConfigRequest(
        @Schema(description = "模型配置名称", example = "官方百炼对话")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "提供方", example = "OPENAI")
        @NotNull
        CredentialProvider provider,

        @Schema(description = "模型类型", example = "CHAT")
        @NotNull
        ModelType modelType,

        @Schema(description = "模型名称", example = "qwen-plus")
        @NotBlank
        @Size(max = 128)
        String modelName,

        @Schema(description = "官方模型凭证 ID", example = "1")
        @NotNull
        Long officialCredentialId,

        @Schema(description = "默认温度", example = "0.2")
        BigDecimal temperature,

        @Schema(description = "默认最大 token", example = "2048")
        @Positive
        Integer maxTokens,

        @Schema(description = "输入单价，单位：人民币/百万 token", example = "2.000000")
        @NotNull
        @DecimalMin(value = "0.0")
        BigDecimal inputPricePerMillion,

        @Schema(description = "输出单价，单位：人民币/百万 token", example = "6.000000")
        @NotNull
        @DecimalMin(value = "0.0")
        BigDecimal outputPricePerMillion,

        @Schema(description = "币种", example = "CNY")
        @NotBlank
        @Size(max = 16)
        String currency,

        @Schema(description = "描述")
        @Size(max = 512)
        String description,

        @Schema(description = "是否启用", example = "true")
        @NotNull
        Boolean enabled
) {
}
