package com.enjoy.agent.workflow.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(name = "WorkflowResponse", description = "工作流响应")
public record WorkflowResponse(
        @Schema(description = "工作流 ID") Long id,
        @Schema(description = "租户 ID") Long tenantId,
        @Schema(description = "名称") String name,
        @Schema(description = "描述") String description,
        @Schema(description = "是否启用") boolean enabled,
        @Schema(description = "节点数量") int nodeCount,
        @Schema(description = "连线数量") int edgeCount,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "最后更新时间") Instant updatedAt
) {
}
