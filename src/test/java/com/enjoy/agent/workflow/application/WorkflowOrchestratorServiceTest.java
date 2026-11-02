package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowExecution;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowEdgeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowExecutionRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class WorkflowOrchestratorServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowNodeRepository nodeRepository;
    @Mock private WorkflowEdgeRepository edgeRepository;
    @Mock private DagExecutor dagExecutor;
    @Mock private NodeExecutorRegistry registry;
    @Mock private ModelConfigRepository modelConfigRepository;
    @Mock private WorkflowExecutionRepository executionRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private McpGatewayService mcpGatewayService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testRunFailsClosedForPlatformFundedModelWithoutAccountingContext() {
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setId(9L);
        modelConfig.setProvider(CredentialProvider.OPENAI);
        modelConfig.setModelType(ModelType.CHAT);
        modelConfig.setModelName("platform-model");
        modelConfig.setCredentialSource(ModelCredentialSource.PLATFORM);
        modelConfig.setEnabled(true);
        when(modelConfigRepository.findByIdAndTenant_Id(9L, 3L)).thenReturn(Optional.of(modelConfig));
        WorkflowOrchestratorService service = new WorkflowOrchestratorService(
                workflowRepository,
                nodeRepository,
                edgeRepository,
                dagExecutor,
                registry,
                modelConfigRepository,
                executionRepository,
                tenantRepository,
                new ObjectMapper(),
                agentRepository,
                mcpGatewayService,
                new WorkflowRuntimeProperties(),
                org.mockito.Mockito.mock(com.enjoy.agent.workflow.application.WorkflowCheckpointService.class));

        assertThatThrownBy(() -> service.resolveModelConfigForTest(9L, 3L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("WORKFLOW_TEST_PLATFORM_MODEL_FORBIDDEN"));
    }

    @Test
    void disconnectedSseCancelsExecutionBeforeDagOrExternalSideEffectsStart() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(
                2L, "owner@example.com", "Owner", 3L, "tenant", "Tenant",
                TenantMemberRole.OWNER, SystemRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token", user.authorities()));
        Tenant tenant = new Tenant();
        tenant.setId(3L);
        Workflow workflow = new Workflow();
        workflow.setId(5L);
        workflow.setTenant(tenant);
        workflow.setName("test");
        workflow.setEnabled(true);
        WorkflowNode start = node(1L, "start", WorkflowNodeType.START, workflow);
        WorkflowNode end = node(2L, "end", WorkflowNodeType.END, workflow);
        when(workflowRepository.findByIdAndTenant_Id(5L, 3L)).thenReturn(Optional.of(workflow));
        when(nodeRepository.findAllByWorkflow_IdOrderByIdAsc(5L)).thenReturn(List.of(start, end));
        when(edgeRepository.findAllByWorkflow_Id(5L)).thenReturn(List.of());
        when(dagExecutor.topologicalSort(any(), any())).thenReturn(List.of(start, end));
        when(tenantRepository.findById(3L)).thenReturn(Optional.of(tenant));
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setId(9L);
        modelConfig.setProvider(CredentialProvider.OPENAI);
        modelConfig.setModelType(ModelType.CHAT);
        modelConfig.setModelName("user-model");
        modelConfig.setCredentialSource(ModelCredentialSource.USER);
        modelConfig.setEnabled(true);
        when(modelConfigRepository.findByIdAndTenant_Id(9L, 3L)).thenReturn(Optional.of(modelConfig));
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<WorkflowExecution> savedExecution = new AtomicReference<>();
        when(executionRepository.save(any(WorkflowExecution.class))).thenAnswer(invocation -> {
            WorkflowExecution execution = invocation.getArgument(0);
            if (execution.getId() == null) {
                execution.setId(77L);
            }
            savedExecution.set(execution);
            if ("CANCELLED".equals(execution.getStatus())) {
                cancelled.countDown();
            }
            return execution;
        });
        WorkflowOrchestratorService service = service();

        service.testRun(5L, "hello", 9L, null, new FailingEmitter());

        assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(savedExecution.get().getStatus()).isEqualTo("CANCELLED");
        verify(dagExecutor, never()).execute(any(), any(), any(), any(), any());
        verify(mcpGatewayService, never()).openTurnSession();
    }

    private WorkflowOrchestratorService service() {
        return new WorkflowOrchestratorService(
                workflowRepository, nodeRepository, edgeRepository, dagExecutor, registry,
                modelConfigRepository, executionRepository, tenantRepository, new ObjectMapper(),
                agentRepository, mcpGatewayService, new WorkflowRuntimeProperties(), org.mockito.Mockito.mock(com.enjoy.agent.workflow.application.WorkflowCheckpointService.class));
    }

    private WorkflowNode node(Long id, String key, WorkflowNodeType type, Workflow workflow) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setNodeKey(key);
        node.setName(key);
        node.setNodeType(type);
        node.setConfigJson("{}");
        node.setWorkflow(workflow);
        return node;
    }

    private static final class FailingEmitter extends SseEmitter {
        private FailingEmitter() { super(1_000L); }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client disconnected");
        }
    }
}
