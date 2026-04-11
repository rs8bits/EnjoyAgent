package com.enjoy.agent.mcp.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 替换 Agent 工具绑定请求。
 */
@Schema(name = "ReplaceAgentToolBindingsRequest", description = "替换某个 Agent 当前绑定的全部工具")
public record ReplaceAgentToolBindingsRequest(
        @Schema(description = "需要绑定到 Agent 的 MCP Tool ID 列表；传空数组表示清空绑定")
        @NotNull
        List<@NotNull Long> toolIds
) {
}
