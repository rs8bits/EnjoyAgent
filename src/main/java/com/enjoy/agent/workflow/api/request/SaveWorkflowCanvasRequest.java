package com.enjoy.agent.workflow.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.util.List;

@Schema(name = "SaveWorkflowCanvasRequest", description = "保存工作流画布请求")
public record SaveWorkflowCanvasRequest(
        @Schema(description = "节点列表")
        @NotNull @jakarta.validation.constraints.Size(max = 200) List<@Valid NodeItem> nodes,

        @Schema(description = "连线列表")
        @NotNull @jakarta.validation.constraints.Size(max = 500) List<@Valid EdgeItem> edges
) {
    @Schema(name = "NodeItem", description = "画布节点")
    public record NodeItem(
            @Schema(description = "节点名称", example = "LLM 调用")
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 128) String name,

            @Schema(description = "节点稳定标识；画布重存后保持不变", example = "answer_llm")
            @jakarta.validation.constraints.Size(max = 64)
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]*") String nodeKey,

            @Schema(description = "节点类型", example = "LLM")
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 32)
            @jakarta.validation.constraints.Pattern(
                    regexp = "(?i)START|LLM|KNOWLEDGE|TOOL|SANDBOX|CONDITION|LOOP|END") String nodeType,

            @Schema(description = "节点配置 JSON", example = "{}")
            @jakarta.validation.constraints.Size(max = 100_000) String configJson,

            @Schema(description = "画布 X 坐标", example = "100")
            int positionX,

            @Schema(description = "画布 Y 坐标", example = "200")
            int positionY
    ) {
        public NodeItem(String name, String nodeType, String configJson, int positionX, int positionY) {
            this(name, null, nodeType, configJson, positionX, positionY);
        }
    }

    @Schema(name = "EdgeItem", description = "画布连线")
    public record EdgeItem(
            @Schema(description = "源节点在请求中的临时索引", example = "0")
            @jakarta.validation.constraints.PositiveOrZero int sourceNodeIndex,

            @Schema(description = "目标节点在请求中的临时索引", example = "1")
            @jakarta.validation.constraints.PositiveOrZero int targetNodeIndex,

            @Schema(description = "源端口", example = "output")
            @jakarta.validation.constraints.Size(max = 64) String sourceHandle,

            @Schema(description = "目标端口", example = "input")
            @jakarta.validation.constraints.Size(max = 64) String targetHandle
    ) {
    }
}
