package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.workflow.api.request.CreateWorkflowRequest;
import com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest;
import com.enjoy.agent.workflow.api.request.UpdateWorkflowRequest;
import com.enjoy.agent.workflow.api.response.WorkflowCanvasResponse;
import com.enjoy.agent.workflow.api.response.WorkflowResponse;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowEdgeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WorkflowApplicationService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowNodeRepository workflowNodeRepository;
    private final WorkflowEdgeRepository workflowEdgeRepository;
    private final TenantRepository tenantRepository;
    private final DagExecutor dagExecutor;

    public WorkflowApplicationService(
            WorkflowRepository workflowRepository,
            WorkflowNodeRepository workflowNodeRepository,
            WorkflowEdgeRepository workflowEdgeRepository,
            TenantRepository tenantRepository,
            DagExecutor dagExecutor
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.tenantRepository = tenantRepository;
        this.dagExecutor = dagExecutor;
    }

    @Transactional
    public WorkflowResponse createWorkflow(CreateWorkflowRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();
        String normalizedName = request.name().trim();

        if (workflowRepository.existsByTenant_IdAndName(tenantId, normalizedName)) {
            throw new ApiException("WORKFLOW_NAME_DUPLICATED", "Workflow name already exists", HttpStatus.CONFLICT);
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Tenant not found", HttpStatus.FORBIDDEN));

        Workflow workflow = new Workflow();
        workflow.setTenant(tenant);
        workflow.setName(normalizedName);
        workflow.setDescription(normalizeDescription(request.description()));
        workflow.setEnabled(true);

        Workflow saved = workflowRepository.saveAndFlush(workflow);
        return toResponse(saved, 0, 0);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowResponse> listWorkflows(Pageable pageable) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return workflowRepository.findAllByTenant_Id(currentUser.tenantId(), pageable)
                .map(w -> {
                    int nodeCount = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(w.getId()).size();
                    int edgeCount = workflowEdgeRepository.findAllByWorkflow_Id(w.getId()).size();
                    return toResponse(w, nodeCount, edgeCount);
                });
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(Long id) {
        Workflow workflow = requireTenantOwnedWorkflow(id);
        int nodeCount = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(workflow.getId()).size();
        int edgeCount = workflowEdgeRepository.findAllByWorkflow_Id(workflow.getId()).size();
        return toResponse(workflow, nodeCount, edgeCount);
    }

    @Transactional
    public WorkflowResponse updateWorkflow(Long id, UpdateWorkflowRequest request) {
        Workflow workflow = requireTenantOwnedWorkflow(id);
        if (request.name() != null) {
            String normalizedName = request.name().trim();
            AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
            if (workflowRepository.existsByTenant_IdAndNameAndIdNot(currentUser.tenantId(), normalizedName, id)) {
                throw new ApiException("WORKFLOW_NAME_DUPLICATED", "Workflow name already exists", HttpStatus.CONFLICT);
            }
            workflow.setName(normalizedName);
        }
        if (request.description() != null) {
            workflow.setDescription(normalizeDescription(request.description()));
        }
        workflow.setEnabled(request.enabled());

        Workflow saved = workflowRepository.saveAndFlush(workflow);
        int nodeCount = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(saved.getId()).size();
        int edgeCount = workflowEdgeRepository.findAllByWorkflow_Id(saved.getId()).size();
        return toResponse(saved, nodeCount, edgeCount);
    }

    @Transactional
    public void deleteWorkflow(Long id) {
        Workflow workflow = requireTenantOwnedWorkflow(id);
        workflowEdgeRepository.deleteAllByWorkflow_Id(workflow.getId());
        workflowNodeRepository.deleteAllByWorkflow_Id(workflow.getId());
        workflowRepository.delete(workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowCanvasResponse getCanvas(Long workflowId) {
        Workflow workflow = requireTenantOwnedWorkflow(workflowId);
        List<WorkflowNode> nodes = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(workflow.getId());
        List<WorkflowEdge> edges = workflowEdgeRepository.findAllByWorkflow_Id(workflow.getId());

        List<WorkflowCanvasResponse.NodeItem> nodeItems = nodes.stream()
                .map(n -> new WorkflowCanvasResponse.NodeItem(
                        n.getId(), n.getName(), n.getNodeType().name(),
                        n.getConfigJson(), n.getPositionX(), n.getPositionY()))
                .toList();
        List<WorkflowCanvasResponse.EdgeItem> edgeItems = edges.stream()
                .map(e -> new WorkflowCanvasResponse.EdgeItem(
                        e.getId(), e.getSourceNode().getId(), e.getTargetNode().getId(),
                        e.getSourceHandle(), e.getTargetHandle()))
                .toList();

        return new WorkflowCanvasResponse(toResponse(workflow, nodes.size(), edges.size()), nodeItems, edgeItems);
    }

    @Transactional
    public WorkflowCanvasResponse saveCanvas(Long workflowId, SaveWorkflowCanvasRequest request) {
        Workflow workflow = requireTenantOwnedWorkflow(workflowId);

        workflowEdgeRepository.deleteAllByWorkflow_Id(workflow.getId());
        workflowNodeRepository.deleteAllByWorkflow_Id(workflow.getId());
        workflowNodeRepository.flush();

        List<WorkflowNode> savedNodes = new ArrayList<>();
        for (SaveWorkflowCanvasRequest.NodeItem item : request.nodes()) {
            WorkflowNode node = new WorkflowNode();
            node.setWorkflow(workflow);
            node.setName(item.name());
            node.setNodeType(WorkflowNodeType.valueOf(item.nodeType().trim().toUpperCase()));
            node.setConfigJson(item.configJson() != null ? item.configJson() : "{}");
            node.setPositionX(item.positionX());
            node.setPositionY(item.positionY());
            savedNodes.add(workflowNodeRepository.save(node));
        }
        workflowNodeRepository.flush();

        List<WorkflowEdge> savedEdges = new ArrayList<>();
        for (SaveWorkflowCanvasRequest.EdgeItem item : request.edges()) {
            if (item.sourceNodeIndex() < 0 || item.sourceNodeIndex() >= savedNodes.size()
                    || item.targetNodeIndex() < 0 || item.targetNodeIndex() >= savedNodes.size()) {
                throw new ApiException("WORKFLOW_EDGE_INDEX_INVALID",
                        "Workflow edge references an invalid node index", HttpStatus.BAD_REQUEST);
            }
            WorkflowNode sourceNode = savedNodes.get(item.sourceNodeIndex());
            WorkflowNode targetNode = savedNodes.get(item.targetNodeIndex());

            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflow(workflow);
            edge.setSourceNode(sourceNode);
            edge.setTargetNode(targetNode);
            edge.setSourceHandle(item.sourceHandle());
            edge.setTargetHandle(item.targetHandle());
            savedEdges.add(workflowEdgeRepository.save(edge));
        }

        dagExecutor.validate(savedNodes, savedEdges);
        dagExecutor.topologicalSort(savedNodes, savedEdges);

        List<WorkflowCanvasResponse.NodeItem> nodeItems = savedNodes.stream()
                .map(n -> new WorkflowCanvasResponse.NodeItem(
                        n.getId(), n.getName(), n.getNodeType().name(),
                        n.getConfigJson(), n.getPositionX(), n.getPositionY()))
                .toList();
        List<WorkflowCanvasResponse.EdgeItem> edgeItems = savedEdges.stream()
                .map(e -> new WorkflowCanvasResponse.EdgeItem(
                        e.getId(), e.getSourceNode().getId(), e.getTargetNode().getId(),
                        e.getSourceHandle(), e.getTargetHandle()))
                .toList();

        return new WorkflowCanvasResponse(
                toResponse(workflow, savedNodes.size(), savedEdges.size()), nodeItems, edgeItems);
    }

    private Workflow requireTenantOwnedWorkflow(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return workflowRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("WORKFLOW_NOT_FOUND", "Workflow not found", HttpStatus.NOT_FOUND));
    }

    private WorkflowResponse toResponse(Workflow workflow, int nodeCount, int edgeCount) {
        return new WorkflowResponse(
                workflow.getId(),
                workflow.getTenant().getId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.isEnabled(),
                nodeCount,
                edgeCount,
                workflow.getCreatedAt(),
                workflow.getUpdatedAt()
        );
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
