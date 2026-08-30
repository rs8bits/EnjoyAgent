package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.mcp.api.response.McpToolResponse;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class McpToolApplicationServiceTest {

    @Mock
    private McpServerApplicationService serverApplicationService;

    @Mock
    private McpToolRepository toolRepository;

    @Mock
    private AgentMcpToolBindingRepository bindingRepository;

    private McpToolApplicationService service;

    @BeforeEach
    void setUp() {
        service = new McpToolApplicationService(serverApplicationService, toolRepository, bindingRepository);
        AuthenticatedUser user = new AuthenticatedUser(
                12L, "owner@example.com", "Owner", 12L, "tenant-12", "Tenant 12",
                TenantMemberRole.OWNER, SystemRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token", user.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replaceServerTools_preservesStableToolIdAndExistingBindingsWhenNameStillExists() {
        Tenant tenant = new Tenant();
        tenant.setId(12L);
        McpServer server = new McpServer();
        server.setId(2L);
        server.setTenant(tenant);
        server.setName("DingTalk MCP");

        McpTool existing = new McpTool();
        existing.setId(31L);
        existing.setTenant(tenant);
        existing.setServer(server);
        existing.setName("send_message");
        existing.setDescription("old description");
        existing.setRiskLevel(McpToolRiskLevel.HIGH);
        existing.setEnabled(true);

        when(serverApplicationService.requireTenantOwnedServer(2L)).thenReturn(server);
        when(toolRepository.findAllByServer_IdOrderByIdAsc(2L)).thenReturn(List.of(existing));
        when(toolRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<McpToolResponse> result = service.replaceServerTools(2L, List.of(
                new DiscoveredMcpTool("send_message", "new description", "{\"type\":\"object\"}")));

        assertThat(result).singleElement().satisfies(tool -> {
            assertThat(tool.id()).isEqualTo(31L);
            assertThat(tool.name()).isEqualTo("send_message");
            assertThat(tool.description()).isEqualTo("new description");
            assertThat(tool.enabled()).isTrue();
        });
        assertThat(existing.getId()).isEqualTo(31L);
        verify(toolRepository).saveAll(List.of(existing));
        verify(toolRepository, never()).deleteAllByIdInBatch(anyList());
        verifyNoInteractions(bindingRepository);
    }
}
