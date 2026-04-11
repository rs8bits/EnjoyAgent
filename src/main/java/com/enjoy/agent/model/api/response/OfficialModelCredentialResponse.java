package com.enjoy.agent.model.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "OfficialModelCredentialResponse", description = "官方模型凭证返回对象")
public record OfficialModelCredentialResponse(
        Long id,
        String name,
        String provider,
        String baseUrl,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
