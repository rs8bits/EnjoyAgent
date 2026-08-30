package com.enjoy.agent.mcp.application;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.api.response.McpToolApprovalResponse;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/** Issues short-lived, user/tenant/agent/tool-bound approvals for HIGH risk tools. */
@Service
public class McpHighRiskApprovalService {

    private static final long APPROVAL_TTL_SECONDS = 120;

    private final AgentMcpToolBindingRepository bindingRepository;
    private final McpApprovalProperties approvalProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RedissonClient redissonClient;

    public McpHighRiskApprovalService(
            AgentMcpToolBindingRepository bindingRepository,
            McpApprovalProperties approvalProperties,
            ObjectMapper objectMapper,
            Clock clock,
            RedissonClient redissonClient
    ) {
        this.bindingRepository = bindingRepository;
        this.approvalProperties = approvalProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.redissonClient = redissonClient;
    }

    @Transactional(readOnly = true)
    public McpToolApprovalResponse issueApproval(Long toolId, Long agentId) {
        AuthenticatedUser user = CurrentUserContext.requireCurrentUser();
        AgentMcpToolBinding binding = bindingRepository.findByAgent_IdAndTool_Id(agentId, toolId)
                .orElseThrow(() -> new ApiException(
                        "MCP_TOOL_NOT_BOUND", "Tool is not bound to this agent", HttpStatus.NOT_FOUND));
        if (!binding.isEnabled()
                || !binding.getTool().isEnabled()
                || !binding.getTool().getServer().isEnabled()
                || !binding.getTenant().getId().equals(user.tenantId())) {
            throw new ApiException("MCP_TOOL_NOT_RUNNABLE", "Tool is not runnable", HttpStatus.BAD_REQUEST);
        }
        if (binding.getTool().getRiskLevel() != McpToolRiskLevel.HIGH) {
            throw new ApiException(
                    "MCP_TOOL_APPROVAL_NOT_REQUIRED", "Only HIGH risk tools require approval", HttpStatus.BAD_REQUEST);
        }

        Instant expiresAt = clock.instant().plusSeconds(APPROVAL_TTL_SECONDS);
        ApprovalClaims claims = new ApprovalClaims(
                user.tenantId(), user.userId(), agentId, toolId, expiresAt.getEpochSecond(), UUID.randomUUID().toString());
        return new McpToolApprovalResponse(toolId, agentId, sign(claims), expiresAt);
    }

    public void requireApproved(PreparedChatTurn turn, PreparedMcpTool tool) {
        if (tool.riskLevel() != McpToolRiskLevel.HIGH) {
            return;
        }
        requireApproved(
                turn.mcpToolApprovalTokens(), turn.tenantId(), turn.userId(), turn.agentId(), tool.id());
    }

    public void requireApproved(
            List<String> tokens,
            Long tenantId,
            Long userId,
            Long agentId,
            Long toolId
    ) {
        boolean approved = tokens != null && tokens.stream()
                .anyMatch(token -> isValid(token, tenantId, userId, agentId, toolId));
        if (!approved) {
            throw new ApiException(
                    "MCP_HIGH_RISK_APPROVAL_REQUIRED",
                    "A valid short-lived approval token is required for this HIGH risk MCP tool",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private boolean isValid(String token, Long tenantId, Long userId, Long agentId, Long toolId) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2) {
                return false;
            }
            byte[] supplied = Base64.getUrlDecoder().decode(parts[1]);
            if (!validSignature(parts[0].getBytes(StandardCharsets.US_ASCII), supplied)) {
                return false;
            }
            ApprovalClaims claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), ApprovalClaims.class);
            boolean claimsMatch = tenantId.equals(claims.tenantId())
                    && userId.equals(claims.userId())
                    && agentId.equals(claims.agentId())
                    && toolId.equals(claims.toolId())
                    && claims.expiresAtEpochSecond() >= clock.instant().getEpochSecond();
            if (!claimsMatch) {
                return false;
            }
            long remainingSeconds = Math.max(
                    1L, claims.expiresAtEpochSecond() - clock.instant().getEpochSecond() + 1L);
            RBucket<Boolean> replayGuard = redissonClient.getBucket(
                    "mcp:approval:consumed:" + tokenDigest(token));
            return replayGuard.setIfAbsent(Boolean.TRUE, Duration.ofSeconds(remainingSeconds));
        } catch (Exception ignored) {
            // Approval is a security boundary: Redis/replay-guard failures are fail-closed.
            return false;
        }
    }

    private String sign(ApprovalClaims claims) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(claims));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac(
                            approvalProperties.getSigningSecret(),
                            payload.getBytes(StandardCharsets.US_ASCII)));
            return payload + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create MCP approval token", ex);
        }
    }

    private boolean validSignature(byte[] payload, byte[] supplied) throws Exception {
        boolean valid = MessageDigest.isEqual(
                hmac(approvalProperties.getSigningSecret(), payload), supplied);
        // Evaluate every configured key instead of returning early, so the verification
        // path does not disclose which rotation key matched.
        for (String previous : approvalProperties.getPreviousSigningSecrets()) {
            valid |= MessageDigest.isEqual(hmac(previous, payload), supplied);
        }
        return valid;
    }

    private byte[] hmac(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(payload);
    }

    private String tokenDigest(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private record ApprovalClaims(
            Long tenantId,
            Long userId,
            Long agentId,
            Long toolId,
            long expiresAtEpochSecond,
            String nonce
    ) {
    }
}
