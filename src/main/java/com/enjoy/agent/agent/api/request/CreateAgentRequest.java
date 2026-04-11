package com.enjoy.agent.agent.api.request;

import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建 Agent 请求。
 */
@Schema(name = "CreateAgentRequest", description = "创建 Agent 请求")
public record CreateAgentRequest(
        @Schema(description = "Agent 名称，同一租户内必须唯一", example = "产品助手")
        @NotBlank
        @Size(max = 128)
        String name,

        @Schema(description = "Agent 描述", example = "帮助回答产品相关问题")
        @Size(max = 512)
        String description,

        @Schema(description = "Agent 的 system prompt", example = "你是一个专业的产品助手，请始终使用简洁中文回答。")
        @NotBlank
        @Size(max = 20000)
        String systemPrompt,

        @Schema(description = "聊天模型绑定类型，USER_MODEL 表示绑定用户模型配置，OFFICIAL_MODEL 表示绑定官方模型配置", example = "USER_MODEL")
        @NotNull
        AgentChatModelBindingType chatModelBindingType,

        @Schema(description = "绑定的用户模型配置 ID，chatModelBindingType=USER_MODEL 时必填", example = "1")
        Long modelConfigId,

        @Schema(description = "绑定的官方模型配置 ID，chatModelBindingType=OFFICIAL_MODEL 时必填", example = "1")
        Long officialModelConfigId,

        @Schema(description = "绑定的知识库 ID，可为空", example = "1")
        Long knowledgeBaseId,

        @Schema(description = "是否启用知识检索二阶段重排", example = "true")
        Boolean rerankEnabled,

        @Schema(description = "绑定的重排模型配置 ID，启用 rerank 时必填", example = "3")
        Long rerankModelConfigId,

        @Schema(description = "上下文策略，MVP 先支持滑动窗口", example = "SLIDING_WINDOW")
        @NotNull
        ContextStrategy contextStrategy,

        @Schema(description = "滑动窗口大小，单位是最近消息条数", example = "12")
        @NotNull
        @Positive
        @Max(100)
        Integer contextWindowSize,

        @Schema(description = "是否启用会话摘要记忆", example = "true")
        Boolean memoryEnabled,

        @Schema(description = "会话记忆策略，当前支持 SESSION_SUMMARY", example = "SESSION_SUMMARY")
        MemoryStrategy memoryStrategy,

        @Schema(description = "新增多少条消息后刷新一次会话摘要", example = "6")
        @Positive
        @Max(100)
        Integer memoryUpdateMessageThreshold,

        @Schema(description = "是否启用", example = "true")
        Boolean enabled
) {
}
