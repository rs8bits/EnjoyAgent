package com.enjoy.agent.knowledge.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求。
 */
@Schema(name = "CreateKnowledgeBaseRequest", description = "创建知识库请求")
public record CreateKnowledgeBaseRequest(
        @Schema(description = "知识库名称，同一租户内必须唯一", example = "产品手册知识库")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "知识库描述", example = "存放产品说明书与常见问题")
        @Size(max = 512)
        String description,

        @Schema(description = "绑定的 embedding 模型配置 ID", example = "3")
        @NotNull
        Long embeddingModelConfigId,

        @Schema(description = "是否启用", example = "true")
        Boolean enabled
) {
}
