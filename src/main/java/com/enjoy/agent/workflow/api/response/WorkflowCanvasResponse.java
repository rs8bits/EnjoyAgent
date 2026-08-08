package com.enjoy.agent.workflow.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "WorkflowCanvasResponse", description = "工作流画布完整数据")
public record WorkflowCanvasResponse(
        @Schema(description = "工作流元数据") WorkflowResponse workflow,
        @Schema(description = "节点列表") List<NodeItem> nodes,
        @Schema(description = "连线列表") List<EdgeItem> edges
) {
    @Schema(name = "WorkflowCanvasNodeItem", description = "画布节点")
    public record NodeItem(
            @Schema(description = "节点 ID") Long id,
            @Schema(description = "节点名称") String name,
            @Schema(description = "节点类型") String nodeType,
            @Schema(description = "节点配置 JSON") String configJson,
            @Schema(description = "画布 X 坐标") int positionX,
            @Schema(description = "画布 Y 坐标") int positionY
    ) {
    }

    @Schema(name = "WorkflowCanvasEdgeItem", description = "画布连线")
    public record EdgeItem(
            @Schema(description = "连线 ID") Long id,
            @Schema(description = "源节点 ID") Long sourceNodeId,
            @Schema(description = "目标节点 ID") Long targetNodeId,
            @Schema(description = "源端口") String sourceHandle,
            @Schema(description = "目标端口") String targetHandle
    ) {
    }
}
