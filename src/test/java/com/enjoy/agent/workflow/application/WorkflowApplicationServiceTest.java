package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowEdgeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class WorkflowApplicationServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowNodeRepository workflowNodeRepository;

    @Mock
    private WorkflowEdgeRepository workflowEdgeRepository;

    @Mock
    private com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository tenantRepository;

    @Mock
    private DagExecutor dagExecutor;

    private WorkflowApplicationService workflowApplicationService;

    @BeforeEach
    void setUp() {
        workflowApplicationService = new WorkflowApplicationService(
                workflowRepository,
                workflowNodeRepository,
                workflowEdgeRepository,
                tenantRepository,
                dagExecutor
        );

        AuthenticatedUser currentUser = new AuthenticatedUser(
                11L,
                "tester@example.com",
                "tester",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.authorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void saveCanvas_shouldRejectOutOfRangeEdgeIndices() {
        Workflow workflow = workflow();
        when(workflowRepository.findByIdAndTenant_Id(3L, 7L)).thenReturn(Optional.of(workflow));
        when(workflowNodeRepository.save(any(WorkflowNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SaveWorkflowCanvasRequest request = new SaveWorkflowCanvasRequest(
                List.of(
                        new SaveWorkflowCanvasRequest.NodeItem("开始", "START", "{}", 100, 100),
                        new SaveWorkflowCanvasRequest.NodeItem("结束", "END", "{}", 300, 100)
                ),
                List.of(
                        new SaveWorkflowCanvasRequest.EdgeItem(0, 2, null, null)
                )
        );

        assertThatThrownBy(() -> workflowApplicationService.saveCanvas(3L, request))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("WORKFLOW_EDGE_INDEX_INVALID");
    }

    @Test
    void saveCanvas_shouldValidateDagAfterSavingNodesAndEdges() {
        Workflow workflow = workflow();
        when(workflowRepository.findByIdAndTenant_Id(3L, 7L)).thenReturn(Optional.of(workflow));
        when(workflowNodeRepository.save(any(WorkflowNode.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowEdgeRepository.save(any(WorkflowEdge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SaveWorkflowCanvasRequest request = new SaveWorkflowCanvasRequest(
                List.of(
                        new SaveWorkflowCanvasRequest.NodeItem("开始", "START", "{}", 100, 100),
                        new SaveWorkflowCanvasRequest.NodeItem("结束", "END", "{}", 300, 100)
                ),
                List.of(
                        new SaveWorkflowCanvasRequest.EdgeItem(0, 1, null, null)
                )
        );

        workflowApplicationService.saveCanvas(3L, request);

        verify(dagExecutor).validate(any(), any());
        verify(dagExecutor).topologicalSort(any(), any());
    }

    private Workflow workflow() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);

        Workflow workflow = new Workflow();
        workflow.setId(3L);
        workflow.setTenant(tenant);
        workflow.setName("测试工作流");
        workflow.setEnabled(true);
        return workflow;
    }
}
