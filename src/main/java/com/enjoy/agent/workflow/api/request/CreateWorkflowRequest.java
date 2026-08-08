package com.enjoy.agent.workflow.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateWorkflowRequest", description = "创建工作流请求")
public record CreateWorkflowRequest(
        @Schema(description = "工作流名称", example = "我的工作流")
        @NotBlank @Size(max = 128) String name,

        @Schema(description = "工作流描述", example = "一个简单的工作流")
        @Size(max = 512) String description
) {
}
