package com.enjoy.agent.model.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(name = "OfficialModelConfigResponse", description = "官方模型配置返回对象")
public record OfficialModelConfigResponse(
        Long id,
        String name,
        String provider,
        String modelType,
        String modelName,
        Long officialCredentialId,
        String officialCredentialName,
        BigDecimal temperature,
        Integer maxTokens,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion,
        String currency,
        String description,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
