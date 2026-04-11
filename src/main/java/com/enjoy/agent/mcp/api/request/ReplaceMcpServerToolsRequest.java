package com.enjoy.agent.mcp.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 替换某个 MCP Server 工具目录的请求。
 */
@Schema(name = "ReplaceMcpServerToolsRequest", description = "替换某个 MCP Server 的工具目录")
public record ReplaceMcpServerToolsRequest(
        @Schema(description = "当前 Server 的完整工具列表快照")
        @NotNull
        List<@Valid UpsertMcpToolRequest> tools
) {
}
