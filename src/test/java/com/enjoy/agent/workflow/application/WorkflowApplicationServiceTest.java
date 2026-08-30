package com.enjoy.agent.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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

    @Test
    void listWorkflows_shouldLoadCountsWithTwoGroupedQueriesForTheCurrentPage() {
        Workflow first = workflow();
        Workflow second = workflow();
        second.setId(4L);
        second.setName("第二个工作流");
        PageRequest pageable = PageRequest.of(0, 20);
        when(workflowRepository.findAllByTenant_Id(7L, pageable))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));
        when(workflowNodeRepository.countGroupedByWorkflowId(List.of(3L, 4L)))
                .thenReturn(List.of(new Object[]{3L, 5L}, new Object[]{4L, 2L}));
        when(workflowEdgeRepository.countGroupedByWorkflowId(List.of(3L, 4L)))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 4L}));

        var page = workflowApplicationService.listWorkflows(pageable);

        assertThat(page.getContent()).extracting(response -> response.nodeCount())
                .containsExactly(5, 2);
        assertThat(page.getContent()).extracting(response -> response.edgeCount())
                .containsExactly(4, 0);
        verify(workflowNodeRepository, never()).findAllByWorkflow_IdOrderByIdAsc(any());
        verify(workflowEdgeRepository, never()).findAllByWorkflow_Id(any());
    }

    @Test
    void listWorkflows_shouldNotRunCountQueriesForAnEmptyPage() {
        PageRequest pageable = PageRequest.of(3, 20);
        when(workflowRepository.findAllByTenant_Id(7L, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(workflowApplicationService.listWorkflows(pageable)).isEmpty();

        verify(workflowNodeRepository, never()).countGroupedByWorkflowId(any());
        verify(workflowEdgeRepository, never()).countGroupedByWorkflowId(any());
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
