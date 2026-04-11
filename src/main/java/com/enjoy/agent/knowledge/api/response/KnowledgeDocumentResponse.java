package com.enjoy.agent.knowledge.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 知识库文档返回对象。
 */
@Schema(name = "KnowledgeDocumentResponse", description = "知识库文档返回对象")
public record KnowledgeDocumentResponse(
        @Schema(description = "文档 ID")
        Long id,

        @Schema(description = "知识库 ID")
        Long knowledgeBaseId,

        @Schema(description = "文件名")
        String fileName,

        @Schema(description = "文件内容类型")
        String contentType,

        @Schema(description = "文件大小")
        Long fileSize,

        @Schema(description = "处理状态")
        String status,

        @Schema(description = "切片数量")
        Integer chunkCount,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
