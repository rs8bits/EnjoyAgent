package com.enjoy.agent.knowledge.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 知识库返回对象。
 */
@Schema(name = "KnowledgeBaseResponse", description = "知识库返回对象")
public record KnowledgeBaseResponse(
        @Schema(description = "知识库 ID")
        Long id,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "知识库名称")
        String name,

        @Schema(description = "知识库描述")
        String description,

        @Schema(description = "Embedding 模型配置 ID")
        Long embeddingModelConfigId,

        @Schema(description = "Embedding 模型配置名称")
        String embeddingModelConfigName,

        @Schema(description = "是否启用")
        boolean enabled,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
