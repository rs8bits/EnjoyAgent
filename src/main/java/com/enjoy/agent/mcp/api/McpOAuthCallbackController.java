package com.enjoy.agent.mcp.api;

import com.enjoy.agent.mcp.application.McpOAuthApplicationService;
import com.enjoy.agent.shared.exception.ApiException;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * MCP OAuth 公共回调入口。
 */
@Hidden
@RestController
public class McpOAuthCallbackController {

    private final McpOAuthApplicationService mcpOAuthApplicationService;

    public McpOAuthCallbackController(McpOAuthApplicationService mcpOAuthApplicationService) {
        this.mcpOAuthApplicationService = mcpOAuthApplicationService;
    }

    @GetMapping(value = "/api/mcp/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription
    ) {
        try {
            mcpOAuthApplicationService.handleCallback(state, code, error, errorDescription);
            return html(HttpStatus.OK, "MCP OAuth connected successfully. You can close this page.");
        } catch (ApiException ex) {
            return html(ex.getStatus(), ex.getMessage());
        }
    }

    private ResponseEntity<String> html(HttpStatus status, String message) {
        String escaped = HtmlUtils.htmlEscape(message == null ? "Unexpected error" : message);
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>MCP OAuth</title></head>"
                + "<body style=\"font-family: sans-serif; padding: 32px;\">"
                + "<h2>MCP OAuth</h2><p>" + escaped + "</p></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }
}
