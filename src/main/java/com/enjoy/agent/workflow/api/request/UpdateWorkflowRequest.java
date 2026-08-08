package com.enjoy.agent.workflow.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "UpdateWorkflowRequest", description = "更新工作流请求")
public record UpdateWorkflowRequest(
        @Schema(description = "工作流名称", example = "我的工作流")
        @Size(max = 128) String name,

        @Schema(description = "工作流描述", example = "一个简单的工作流")
        @Size(max = 512) String description,

        @Schema(description = "是否启用")
        @NotNull Boolean enabled
) {
}
