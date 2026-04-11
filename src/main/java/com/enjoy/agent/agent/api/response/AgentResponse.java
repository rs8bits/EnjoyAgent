package com.enjoy.agent.agent.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Agent 返回对象。
 */
@Schema(name = "AgentResponse", description = "Agent 基础配置返回对象")
public record AgentResponse(
        @Schema(description = "Agent ID", example = "1")
        Long id,

        @Schema(description = "所属租户 ID", example = "1")
        Long tenantId,

        @Schema(description = "Agent 名称", example = "产品助手")
        String name,

        @Schema(description = "Agent 描述", example = "帮助回答产品相关问题")
        String description,

        @Schema(description = "Agent 的 system prompt")
        String systemPrompt,

        @Schema(description = "聊天模型绑定类型", example = "USER_MODEL")
        String chatModelBindingType,

        @Schema(description = "绑定的用户模型配置 ID", example = "1")
        Long modelConfigId,

        @Schema(description = "绑定的用户模型配置名称", example = "默认对话模型")
        String modelConfigName,

        @Schema(description = "绑定的官方模型配置 ID", example = "1")
        Long officialModelConfigId,

        @Schema(description = "绑定的官方模型配置名称", example = "官方百炼对话")
        String officialModelConfigName,

        @Schema(description = "绑定的知识库 ID")
        Long knowledgeBaseId,

        @Schema(description = "绑定的知识库名称")
        String knowledgeBaseName,

        @Schema(description = "是否启用知识检索二阶段重排", example = "true")
        boolean rerankEnabled,

        @Schema(description = "绑定的重排模型配置 ID")
        Long rerankModelConfigId,

        @Schema(description = "绑定的重排模型配置名称")
        String rerankModelConfigName,

        @Schema(description = "上下文策略", example = "SLIDING_WINDOW")
        String contextStrategy,

        @Schema(description = "滑动窗口大小", example = "12")
        Integer contextWindowSize,

        @Schema(description = "是否启用会话摘要记忆", example = "true")
        boolean memoryEnabled,

        @Schema(description = "会话记忆策略")
        String memoryStrategy,

        @Schema(description = "新增多少条消息后刷新一次会话摘要", example = "6")
        Integer memoryUpdateMessageThreshold,

        @Schema(description = "是否启用", example = "true")
        boolean enabled,

        @Schema(description = "创建时间")
        Instant createdAt,

        @Schema(description = "更新时间")
        Instant updatedAt
) {
}
