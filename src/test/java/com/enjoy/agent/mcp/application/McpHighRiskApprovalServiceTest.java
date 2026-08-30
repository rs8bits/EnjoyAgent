package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.mcp.api.response.McpToolApprovalResponse;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class McpHighRiskApprovalServiceTest {

    @Mock
    private AgentMcpToolBindingRepository bindingRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<Boolean> replayGuard;

    private McpHighRiskApprovalService service;

    @BeforeEach
    void setUp() {
        McpApprovalProperties approvalProperties = approvalProperties(
                "0123456789abcdef0123456789abcdef", List.of());
        service = new McpHighRiskApprovalService(
                bindingRepository,
                approvalProperties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                redissonClient
        );
        when(redissonClient.<Boolean>getBucket(anyString())).thenReturn(replayGuard);
        AuthenticatedUser user = new AuthenticatedUser(
                7L, "member@example.com", "Member", 3L, "tenant", "Tenant",
                TenantMemberRole.MEMBER, SystemRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token", user.authorities()));
    }

    @Test
    void tokenIssuedBeforeRotationIsAcceptedByPreviousKeyOnlyOnce() {
        when(bindingRepository.findByAgent_IdAndTool_Id(11L, 13L))
                .thenReturn(Optional.of(highRiskBinding()));
        McpToolApprovalResponse oldApproval = service.issueApproval(13L, 11L);

        McpHighRiskApprovalService rotated = new McpHighRiskApprovalService(
                bindingRepository,
                approvalProperties(
                        "fedcba9876543210fedcba9876543210",
                        List.of("0123456789abcdef0123456789abcdef")),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                redissonClient
        );
        when(replayGuard.setIfAbsent(org.mockito.ArgumentMatchers.eq(Boolean.TRUE), any(java.time.Duration.class)))
                .thenReturn(true, false);

        assertDoesNotThrow(() -> rotated.requireApproved(
                List.of(oldApproval.approvalToken()), 3L, 7L, 11L, 13L));
        assertThatThrownBy(() -> rotated.requireApproved(
                List.of(oldApproval.approvalToken()), 3L, 7L, 11L, 13L))
                .isInstanceOf(ApiException.class);
    }

    private McpApprovalProperties approvalProperties(String current, List<String> previous) {
        McpApprovalProperties properties = new McpApprovalProperties();
        properties.setSigningSecret(current);
        properties.setPreviousSigningSecrets(previous);
        properties.validate();
        return properties;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approvalIsBoundToTenantUserAgentAndTool() {
        when(bindingRepository.findByAgent_IdAndTool_Id(11L, 13L))
                .thenReturn(Optional.of(highRiskBinding()));

        McpToolApprovalResponse approval = service.issueApproval(13L, 11L);
        when(replayGuard.setIfAbsent(org.mockito.ArgumentMatchers.eq(Boolean.TRUE), any(java.time.Duration.class)))
                .thenReturn(true, false);

        assertDoesNotThrow(() -> service.requireApproved(
                List.of(approval.approvalToken()), 3L, 7L, 11L, 13L));
        assertThatThrownBy(() -> service.requireApproved(
                List.of(approval.approvalToken()), 3L, 7L, 11L, 13L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireApproved(
                List.of(approval.approvalToken()), 3L, 8L, 11L, 13L))
                .isInstanceOf(ApiException.class);
    }

    private AgentMcpToolBinding highRiskBinding() {
        Tenant tenant = new Tenant();
        tenant.setId(3L);
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setTenant(tenant);
        McpServer server = new McpServer();
        server.setId(17L);
        server.setTenant(tenant);
        server.setEnabled(true);
        McpTool tool = new McpTool();
        tool.setId(13L);
        tool.setTenant(tenant);
        tool.setServer(server);
        tool.setRiskLevel(McpToolRiskLevel.HIGH);
        tool.setEnabled(true);
        AgentMcpToolBinding binding = new AgentMcpToolBinding();
        binding.setTenant(tenant);
        binding.setAgent(agent);
        binding.setTool(tool);
        binding.setEnabled(true);
        return binding;
    }
}
