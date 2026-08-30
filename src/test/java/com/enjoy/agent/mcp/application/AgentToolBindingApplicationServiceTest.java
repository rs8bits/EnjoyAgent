package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.mcp.api.request.ReplaceAgentToolBindingsRequest;
import com.enjoy.agent.mcp.api.response.AgentToolBindingResponse;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class AgentToolBindingApplicationServiceTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private McpToolRepository mcpToolRepository;

    @Mock
    private AgentMcpToolBindingRepository bindingRepository;

    private AgentToolBindingApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AgentToolBindingApplicationService(agentRepository, mcpToolRepository, bindingRepository);
        authenticate(TenantMemberRole.OWNER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replaceBindings_bindsEnabledToolDiscoveredForCurrentTenant() {
        Tenant tenant = tenant();
        Agent agent = agent(tenant);
        McpTool tool = tool(31L, "send_message", tenant);
        AgentMcpToolBinding stored = binding(81L, agent, tool);

        when(agentRepository.findByIdAndTenant_Id(7L, 12L)).thenReturn(Optional.of(agent));
        when(mcpToolRepository.findAllByIdInAndTenant_Id(anyCollection(), eq(12L))).thenReturn(List.of(tool));
        when(bindingRepository.findAllByAgent_IdOrderByIdAsc(7L))
                .thenReturn(List.of())
                .thenReturn(List.of(stored));

        List<AgentToolBindingResponse> result = service.replaceBindings(
                7L, new ReplaceAgentToolBindingsRequest(List.of(31L)));

        assertThat(result).singleElement().satisfies(response -> {
            assertThat(response.agentId()).isEqualTo(7L);
            assertThat(response.toolId()).isEqualTo(31L);
            assertThat(response.toolName()).isEqualTo("send_message");
            assertThat(response.enabled()).isTrue();
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentMcpToolBinding>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(bindingRepository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).singleElement().satisfies(saved -> {
            assertThat(saved.getTenant()).isSameAs(tenant);
            assertThat(saved.getAgent()).isSameAs(agent);
            assertThat(saved.getTool()).isSameAs(tool);
            assertThat(saved.isEnabled()).isTrue();
        });
        verify(bindingRepository, never()).deleteAllInBatch(anyList());
    }

    @Test
    void replaceBindings_preservesRequestedBindingAndRemovesOnlyDeselectedTool() {
        Tenant tenant = tenant();
        Agent agent = agent(tenant);
        McpTool retainedTool = tool(31L, "send_message", tenant);
        McpTool removedTool = tool(32L, "read_calendar", tenant);
        AgentMcpToolBinding retained = binding(81L, agent, retainedTool);
        retained.setEnabled(false);
        AgentMcpToolBinding removed = binding(82L, agent, removedTool);

        when(agentRepository.findByIdAndTenant_Id(7L, 12L)).thenReturn(Optional.of(agent));
        when(mcpToolRepository.findAllByIdInAndTenant_Id(anyCollection(), eq(12L))).thenReturn(List.of(retainedTool));
        when(bindingRepository.findAllByAgent_IdOrderByIdAsc(7L))
                .thenReturn(List.of(retained, removed))
                .thenReturn(List.of(retained));

        List<AgentToolBindingResponse> result = service.replaceBindings(
                7L, new ReplaceAgentToolBindingsRequest(List.of(31L)));

        assertThat(result).extracting(AgentToolBindingResponse::toolId).containsExactly(31L);
        assertThat(retained.isEnabled()).isTrue();
        verify(bindingRepository).deleteAllInBatch(List.of(removed));
        verify(bindingRepository).saveAll(List.of(retained));
    }

    @Test
    void replaceBindings_rejectsToolOutsideCurrentTenant() {
        Tenant tenant = tenant();
        Agent agent = agent(tenant);
        when(agentRepository.findByIdAndTenant_Id(7L, 12L)).thenReturn(Optional.of(agent));
        when(mcpToolRepository.findAllByIdInAndTenant_Id(anyCollection(), eq(12L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.replaceBindings(
                7L, new ReplaceAgentToolBindingsRequest(List.of(999L))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("MCP_TOOL_NOT_FOUND"));

        verifyNoInteractions(bindingRepository);
    }

    @Test
    void replaceBindings_requiresTenantOwner() {
        authenticate(TenantMemberRole.MEMBER);

        assertThatThrownBy(() -> service.replaceBindings(
                7L, new ReplaceAgentToolBindingsRequest(List.of(31L))))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("TENANT_OWNER_REQUIRED"));

        verifyNoInteractions(agentRepository, mcpToolRepository, bindingRepository);
    }

    private void authenticate(TenantMemberRole role) {
        AuthenticatedUser user = new AuthenticatedUser(
                12L, "owner@example.com", "Owner", 12L, "tenant-12", "Tenant 12", role, SystemRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token", user.authorities()));
    }

    private Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.setId(12L);
        return tenant;
    }

    private Agent agent(Tenant tenant) {
        Agent agent = new Agent();
        agent.setId(7L);
        agent.setTenant(tenant);
        return agent;
    }

    private McpTool tool(Long id, String name, Tenant tenant) {
        McpServer server = new McpServer();
        server.setId(2L);
        server.setTenant(tenant);
        server.setName("DingTalk MCP");
        server.setEnabled(true);

        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setTenant(tenant);
        tool.setServer(server);
        tool.setName(name);
        tool.setRiskLevel(McpToolRiskLevel.HIGH);
        tool.setEnabled(true);
        return tool;
    }

    private AgentMcpToolBinding binding(Long id, Agent agent, McpTool tool) {
        AgentMcpToolBinding binding = new AgentMcpToolBinding();
        binding.setId(id);
        binding.setTenant(agent.getTenant());
        binding.setAgent(agent);
        binding.setTool(tool);
        binding.setEnabled(true);
        return binding;
    }
}
