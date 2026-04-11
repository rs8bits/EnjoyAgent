package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.api.request.ConnectMcpOAuthClientCredentialsRequest;
import com.enjoy.agent.mcp.api.request.StartMcpOAuthAuthorizationRequest;
import com.enjoy.agent.mcp.api.response.McpOAuthAuthorizationResponse;
import com.enjoy.agent.mcp.api.response.McpOAuthConnectionResponse;
import com.enjoy.agent.mcp.application.McpOAuthApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP OAuth 管理接口。
 */
@Tag(name = "MCP OAuth", description = "MCP OAuth 授权与连接状态接口")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/mcp/servers/{serverId}/oauth")
public class McpOAuthController {

    private final McpOAuthApplicationService mcpOAuthApplicationService;

    public McpOAuthController(McpOAuthApplicationService mcpOAuthApplicationService) {
        this.mcpOAuthApplicationService = mcpOAuthApplicationService;
    }

    @Operation(summary = "发起 MCP OAuth 授权", description = "完成元数据发现、生成 PKCE 参数，并返回可跳转的 authorizationUrl")
    @PostMapping("/authorize")
    public ApiResponse<McpOAuthAuthorizationResponse> startAuthorization(
            @PathVariable Long serverId,
            @Valid @RequestBody StartMcpOAuthAuthorizationRequest request
    ) {
        return ApiResponse.success(
                mcpOAuthApplicationService.startAuthorization(serverId, request),
                "MCP OAuth authorization URL generated"
        );
    }

    @Operation(summary = "使用 client credentials 建立 MCP OAuth 连接", description = "适用于无需浏览器回跳的 OAuth 服务器，直接用 client_id/client_secret 换取 access token")
    @PostMapping("/connect-client-credentials")
    public ApiResponse<McpOAuthConnectionResponse> connectClientCredentials(
            @PathVariable Long serverId,
            @Valid @RequestBody ConnectMcpOAuthClientCredentialsRequest request
    ) {
        return ApiResponse.success(
                mcpOAuthApplicationService.connectClientCredentials(serverId, request),
                "MCP OAuth client credentials connected"
        );
    }

    @Operation(summary = "查询 MCP OAuth 连接状态", description = "查看当前 Server 的 OAuth 连接状态、token 过期时间和最近错误")
    @GetMapping("/connection")
    public ApiResponse<McpOAuthConnectionResponse> getConnection(@PathVariable Long serverId) {
        return ApiResponse.success(mcpOAuthApplicationService.getConnection(serverId));
    }

    @Operation(summary = "断开 MCP OAuth 连接", description = "删除当前 Server 已保存的 OAuth token 与授权状态")
    @DeleteMapping("/connection")
    public ApiResponse<Void> disconnect(@PathVariable Long serverId) {
        mcpOAuthApplicationService.disconnect(serverId);
        return ApiResponse.success(null, "MCP OAuth connection deleted");
    }
}
