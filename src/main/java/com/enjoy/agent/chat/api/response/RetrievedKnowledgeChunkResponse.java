package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 检索命中的知识片段响应。
 */
@Schema(name = "RetrievedKnowledgeChunkResponse", description = "一次知识检索命中的知识片段")
public record RetrievedKnowledgeChunkResponse(
        @Schema(description = "知识片段 ID")
        Long chunkId,

        @Schema(description = "来源文档 ID")
        Long documentId,

        @Schema(description = "来源文档名称")
        String documentName,

        @Schema(description = "片段索引")
        Integer chunkIndex,

        @Schema(description = "混合召回融合分数")
        Double recallScore,

        @Schema(description = "混合召回阶段的名次")
        Integer recallRank,

        @Schema(description = "语义召回分数")
        Double denseScore,

        @Schema(description = "语义召回名次")
        Integer denseRank,

        @Schema(description = "关键词召回分数")
        Double lexicalScore,

        @Schema(description = "关键词召回名次")
        Integer lexicalRank,

        @Schema(description = "该片段由哪一路召回命中", example = "BOTH")
        String matchedBy,

        @Schema(description = "重排分数")
        Double rerankScore,

        @Schema(description = "重排后的名次")
        Integer rerankRank,

        @Schema(description = "是否进入最终上下文")
        boolean selected,

        @Schema(description = "片段内容")
        String content
) {
}
