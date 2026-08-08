package com.enjoy.agent.workflow.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(name = "SaveWorkflowCanvasRequest", description = "保存工作流画布请求")
public record SaveWorkflowCanvasRequest(
        @Schema(description = "节点列表")
        @NotNull List<NodeItem> nodes,

        @Schema(description = "连线列表")
        @NotNull List<EdgeItem> edges
) {
    @Schema(name = "NodeItem", description = "画布节点")
    public record NodeItem(
            @Schema(description = "节点名称", example = "LLM 调用")
            @jakarta.validation.constraints.NotBlank String name,

            @Schema(description = "节点类型", example = "LLM")
            @jakarta.validation.constraints.NotBlank String nodeType,

            @Schema(description = "节点配置 JSON", example = "{}")
            String configJson,

            @Schema(description = "画布 X 坐标", example = "100")
            int positionX,

            @Schema(description = "画布 Y 坐标", example = "200")
            int positionY
    ) {
    }

    @Schema(name = "EdgeItem", description = "画布连线")
    public record EdgeItem(
            @Schema(description = "源节点在请求中的临时索引", example = "0")
            int sourceNodeIndex,

            @Schema(description = "目标节点在请求中的临时索引", example = "1")
            int targetNodeIndex,

            @Schema(description = "源端口", example = "output")
            String sourceHandle,

            @Schema(description = "目标端口", example = "input")
            String targetHandle
    ) {
    }
}
