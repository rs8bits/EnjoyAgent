package com.enjoy.agent.chat.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 聊天轮次中的知识检索调试信息。
 */
@Schema(name = "KnowledgeRetrievalDebugResponse", description = "知识检索调试信息")
public record KnowledgeRetrievalDebugResponse(
        @Schema(description = "知识库 ID")
        Long knowledgeBaseId,

        @Schema(description = "知识库名称")
        String knowledgeBaseName,

        @Schema(description = "原始用户问题")
        String originalQuery,

        @Schema(description = "最终用于检索的查询")
        String retrievalQuery,

        @Schema(description = "是否应用了查询改写")
        boolean rewriteApplied,

        @Schema(description = "召回阶段候选数量")
        Integer recallTopK,

        @Schema(description = "最终注入模型的片段数量")
        Integer finalTopK,

        @Schema(description = "是否应用了重排")
        boolean rerankApplied,

        @Schema(description = "当前使用的重排模型")
        String rerankModel,

        @Schema(description = "命中的知识片段")
        List<RetrievedKnowledgeChunkResponse> hits
) {
}
