package com.enjoy.agent.mcp.api.response;

import java.time.Instant;

public record McpToolApprovalResponse(
        Long toolId,
        Long agentId,
        String approvalToken,
        Instant expiresAt
) {
}
