package com.enjoy.agent.model.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 模型配置返回对象。
 */
@Schema(name = "ModelConfigResponse", description = "模型配置返回对象")
public record ModelConfigResponse(
        @Schema(description = "模型配置 ID", example = "1")
        Long id,

        @Schema(description = "租户 ID", example = "1")
        Long tenantId,

        @Schema(description = "配置名称", example = "默认对话模型")
        String name,

        @Schema(description = "模型提供方", example = "OPENAI")
        String provider,

        @Schema(description = "模型类型", example = "CHAT")
        String modelType,

        @Schema(description = "模型名称", example = "gpt-4o-mini")
        String modelName,

        @Schema(description = "密钥来源", example = "USER")
        String credentialSource,

        @Schema(description = "绑定的用户凭证 ID", example = "1")
        Long credentialId,

        @Schema(description = "绑定的凭证名称", example = "我的 OpenAI Key")
        String credentialName,

        @Schema(description = "采样温度", example = "0.2")
        BigDecimal temperature,

        @Schema(description = "最大输出 token 数", example = "2048")
        Integer maxTokens,

        @Schema(description = "是否启用", example = "true")
        boolean enabled,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
