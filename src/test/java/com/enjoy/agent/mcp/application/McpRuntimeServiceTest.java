package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.mcp.domain.entity.AgentMcpToolBinding;
import com.enjoy.agent.mcp.domain.entity.McpServer;
import com.enjoy.agent.mcp.domain.entity.McpTool;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import com.enjoy.agent.mcp.infrastructure.persistence.AgentMcpToolBindingRepository;
import com.enjoy.agent.mcp.infrastructure.persistence.McpServerRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpRuntimeServiceTest {

    @Mock
    private McpServerRepository mcpServerRepository;

    @Mock
    private AgentMcpToolBindingRepository agentMcpToolBindingRepository;

    @Mock
    private McpGatewayService mcpGatewayService;

    @Mock
    private McpToolApplicationService mcpToolApplicationService;

    @Mock
    private McpOAuthApplicationService mcpOAuthApplicationService;

    @Mock
    private AesCryptoService aesCryptoService;

    @Test
    void listRunnableToolsForAgent_resolvesBearerTokenForClientCredentialsServer() {
        McpRuntimeService service = new McpRuntimeService(
                mcpServerRepository,
                agentMcpToolBindingRepository,
                mcpGatewayService,
                mcpToolApplicationService,
                mcpOAuthApplicationService,
                aesCryptoService
        );

        McpServer server = server(McpAuthType.OAUTH_CLIENT_CREDENTIALS, null);
        McpTool tool = tool(server);
        AgentMcpToolBinding binding = binding(tool);
        when(agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(1L)).thenReturn(List.of(binding));
        when(mcpOAuthApplicationService.resolveAccessTokenForRuntime(server)).thenReturn("oauth-token");

        List<PreparedMcpTool> preparedTools = service.listRunnableToolsForAgent(1L);

        assertThat(preparedTools).hasSize(1);
        assertThat(preparedTools.getFirst().server().bearerToken()).isEqualTo("oauth-token");
        verify(mcpOAuthApplicationService).resolveAccessTokenForRuntime(server);
        verifyNoInteractions(aesCryptoService);
    }

    @Test
    void listRunnableToolsForAgent_decryptsStaticBearerCredential() {
        McpRuntimeService service = new McpRuntimeService(
                mcpServerRepository,
                agentMcpToolBindingRepository,
                mcpGatewayService,
                mcpToolApplicationService,
                mcpOAuthApplicationService,
                aesCryptoService
        );

        Credential credential = new Credential();
        credential.setSecretCiphertext("ciphertext");
        McpServer server = server(McpAuthType.STATIC_BEARER, credential);
        McpTool tool = tool(server);
        AgentMcpToolBinding binding = binding(tool);
        when(agentMcpToolBindingRepository.findAllByAgent_IdOrderByIdAsc(1L)).thenReturn(List.of(binding));
        when(aesCryptoService.decrypt("ciphertext")).thenReturn("static-token");

        List<PreparedMcpTool> preparedTools = service.listRunnableToolsForAgent(1L);

        assertThat(preparedTools).hasSize(1);
        assertThat(preparedTools.getFirst().server().bearerToken()).isEqualTo("static-token");
        verify(aesCryptoService).decrypt("ciphertext");
        verifyNoInteractions(mcpOAuthApplicationService);
    }

    private McpServer server(McpAuthType authType, Credential credential) {
        McpServer server = new McpServer();
        server.setId(20L);
        server.setName("demo-server");
        server.setBaseUrl("https://example.com/mcp");
        server.setTransportType(McpTransportType.STREAMABLE_HTTP);
        server.setAuthType(authType);
        server.setCredential(credential);
        server.setEnabled(true);
        return server;
    }

    private McpTool tool(McpServer server) {
        McpTool tool = new McpTool();
        tool.setId(10L);
        tool.setServer(server);
        tool.setName("demo_tool");
        tool.setDescription("demo");
        tool.setRiskLevel(McpToolRiskLevel.LOW);
        tool.setEnabled(true);
        return tool;
    }

    private AgentMcpToolBinding binding(McpTool tool) {
        AgentMcpToolBinding binding = new AgentMcpToolBinding();
        binding.setTool(tool);
        binding.setEnabled(true);
        return binding;
    }
}
