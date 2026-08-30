package com.enjoy.agent.workflow.application;

import com.enjoy.agent.agent.domain.entity.Agent;
import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.modelgateway.application.ModelGatewayResult;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.mcp.application.McpGatewayService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest;
import com.enjoy.agent.workflow.domain.entity.Workflow;
import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import com.enjoy.agent.workflow.domain.entity.WorkflowExecution;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowEdgeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowExecutionRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowNodeRepository;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class WorkflowOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOrchestratorService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowNodeRepository workflowNodeRepository;
    private final WorkflowEdgeRepository workflowEdgeRepository;
    private final DagExecutor dagExecutor;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final ModelConfigRepository modelConfigRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;
    private final AgentRepository agentRepository;
    private final McpGatewayService mcpGatewayService;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowOutputSanitizer outputSanitizer = new WorkflowOutputSanitizer();
    private final Map<Long, ActiveTestRun> activeTestRuns = new ConcurrentHashMap<>();

    public WorkflowOrchestratorService(
            WorkflowRepository workflowRepository,
            WorkflowNodeRepository workflowNodeRepository,
            WorkflowEdgeRepository workflowEdgeRepository,
            DagExecutor dagExecutor,
            NodeExecutorRegistry nodeExecutorRegistry,
            ModelConfigRepository modelConfigRepository,
            WorkflowExecutionRepository executionRepository,
            TenantRepository tenantRepository,
            ObjectMapper objectMapper,
            AgentRepository agentRepository,
            McpGatewayService mcpGatewayService,
            WorkflowRuntimeProperties runtimeProperties
    ) {
        this.workflowRepository = workflowRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.dagExecutor = dagExecutor;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.modelConfigRepository = modelConfigRepository;
        this.executionRepository = executionRepository;
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
        this.agentRepository = agentRepository;
        this.mcpGatewayService = mcpGatewayService;
        this.runtimeProperties = runtimeProperties;
    }

    public ModelGatewayResult executeWorkflow(PreparedChatTurn turn) {
        Workflow workflow = workflowRepository.findByIdAndTenant_Id(turn.workflowId(), turn.tenantId())
                .orElseThrow(() -> new ApiException("WORKFLOW_NOT_FOUND", "Workflow not found", HttpStatus.INTERNAL_SERVER_ERROR));
        if (!workflow.isEnabled()) {
            throw new ApiException("WORKFLOW_DISABLED", "Workflow is disabled", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        List<WorkflowNode> nodes = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(workflow.getId());
        List<WorkflowEdge> edges = workflowEdgeRepository.findAllByWorkflow_Id(workflow.getId());
        dagExecutor.validate(nodes, edges);
        List<WorkflowNode> sortedNodes = dagExecutor.topologicalSort(nodes, edges);

        WorkflowExecution execution = beginExecution(
                workflow, turn.latestUserMessageContent(), "CHAT", sortedNodes.size(), turn.tenantId());
        long startMs = System.currentTimeMillis();
        WorkflowExecutionBudget executionBudget = new WorkflowExecutionBudget(runtimeProperties);

        try (McpGatewayService.McpTurnSession mcpTurnSession = mcpGatewayService.openTurnSession()) {
            Map<String, Object> initialContext = new HashMap<>();
            initialContext.put("user_input", turn.latestUserMessageContent());
            initialContext.put("history_messages", turn.historyMessages());
            initialContext.put("system_prompt", turn.systemPrompt());
            initialContext.put("mcp_tool_approval_tokens", turn.mcpToolApprovalTokens());
            if (turn.sessionMemory() != null && turn.sessionMemory().summary() != null) {
                initialContext.put("session_memory_summary", turn.sessionMemory().summary());
            }

            NodeExecutionContext baseCtx = new NodeExecutionContext(
                    turn.tenantId(), turn.userId(), turn.agentId(), turn.sessionId(),
                    sortedNodes.get(0), turn.modelConfig(), initialContext, turn, mcpTurnSession,
                    executionBudget, indexWorkflowNodes(nodes)
            );

            Map<String, Object> finalContext = dagExecutor.execute(sortedNodes, edges, nodeExecutorRegistry, baseCtx);
            String finalOutput = extractFinalOutput(finalContext);
            completeExecution(execution, "COMPLETED", startMs, finalOutput, finalContext);
            return buildResult(finalContext, finalOutput, turn, executionBudget);
        } catch (RuntimeException ex) {
            completeExecution(execution, "FAILED", startMs, null, null);
            execution.setErrorMessage(truncate(ex.getMessage(), 1000));
            executionRepository.save(execution);
            throw ex;
        }
    }

    public void testRun(
            Long workflowId,
            String userInput,
            Long modelConfigId,
            SaveWorkflowCanvasRequest draftCanvas,
            SseEmitter emitter
    ) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        AtomicReference<WorkflowExecutionBudget> budgetReference = new AtomicReference<>();
        AtomicBoolean clientDisconnected = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable task = () -> {
            Workflow workflow = null;
            WorkflowExecution execution = null;
            long startMs = System.currentTimeMillis();
            int totalNodes = 0;
            try {
                workflow = workflowRepository.findByIdAndTenant_Id(workflowId, currentUser.tenantId())
                        .orElseThrow(() -> new ApiException("WORKFLOW_NOT_FOUND", "Workflow not found", HttpStatus.NOT_FOUND));
                WorkflowGraph graph = resolveWorkflowGraph(workflow, draftCanvas);
                List<WorkflowNode> nodes = graph.nodes();
                List<WorkflowEdge> edges = graph.edges();
                dagExecutor.validate(nodes, edges);
                List<WorkflowNode> sortedNodes = dagExecutor.topologicalSort(nodes, edges);
                totalNodes = sortedNodes.size();

                execution = beginExecution(workflow, userInput, "TEST", totalNodes, currentUser.tenantId());

                PreparedModelConfig modelConfig = resolveModelConfigForTest(modelConfigId, currentUser.tenantId());
                WorkflowExecutionBudget executionBudget = new WorkflowExecutionBudget(runtimeProperties);
                budgetReference.set(executionBudget);
                activeTestRuns.put(execution.getId(), new ActiveTestRun(
                        currentUser.tenantId(), executionBudget, Thread.currentThread()));
                sendEventOrCancel(emitter, executionBudget, "started", Map.of(
                        "executionId", execution.getId(), "totalNodes", totalNodes));

                Map<String, Object> initialContext = new HashMap<>();
                initialContext.put("user_input", userInput);
                initialContext.put("history_messages", List.of());
                initialContext.put("system_prompt", "");

                Map<String, Object> finalContext;
                try (McpGatewayService.McpTurnSession mcpTurnSession = mcpGatewayService.openTurnSession()) {
                    NodeExecutionContext baseCtx = new NodeExecutionContext(
                            workflow.getTenant().getId(), currentUser.userId(), resolveAgentIdForTest(workflow),
                            null, sortedNodes.get(0), modelConfig, initialContext, null, mcpTurnSession,
                            executionBudget, indexWorkflowNodes(nodes));
                    finalContext = dagExecutor.execute(sortedNodes, edges, nodeExecutorRegistry, baseCtx, event -> {
                        sendEventOrCancel(
                                emitter, executionBudget, event.type(), outputSanitizer.sanitizeEvent(event));
                    });
                }

                String finalOutput = extractFinalOutput(finalContext);
                completeExecution(execution, "COMPLETED", startMs, finalOutput, finalContext);
                sendEventOrCancel(emitter, executionBudget, "completed", Map.of(
                        "finalOutput", outputSanitizer.sanitizeText(finalOutput),
                        "totalNodes", totalNodes,
                        "executedContext", outputSanitizer.sanitizeMap(finalContext)
                ));
                finished.set(true);
                emitter.complete();
            } catch (ApiException ex) {
                boolean cancelled = clientDisconnected.get()
                        || "WORKFLOW_EXECUTION_CANCELLED".equals(ex.getCode())
                        || "WORKFLOW_CLIENT_DISCONNECTED".equals(ex.getCode());
                if (execution != null) {
                    if (cancelled) {
                        Thread.interrupted();
                    }
                    completeExecution(execution, cancelled ? "CANCELLED" : "FAILED", startMs, null, null);
                    execution.setErrorMessage(truncate(cancelled ? "Workflow execution cancelled" : ex.getMessage(), 1000));
                    executionRepository.save(execution);
                }
                if (!clientDisconnected.get()) {
                    finished.set(true);
                    sendErrorAndComplete(emitter, ex.getCode(), ex.getMessage());
                }
            } catch (Exception ex) {
                boolean cancelled = clientDisconnected.get() || Thread.currentThread().isInterrupted();
                if (execution != null) {
                    if (cancelled) {
                        Thread.interrupted();
                    }
                    completeExecution(execution, cancelled ? "CANCELLED" : "FAILED", startMs, null, null);
                    execution.setErrorMessage(truncate(
                            cancelled ? "Workflow execution cancelled" : "Workflow test-run failed", 1000));
                    executionRepository.save(execution);
                }
                if (!clientDisconnected.get()) {
                    finished.set(true);
                    sendErrorAndComplete(emitter,
                            cancelled ? "WORKFLOW_EXECUTION_CANCELLED" : "TEST_RUN_FAILED",
                            cancelled ? "Workflow execution was cancelled" : "Workflow test-run failed");
                }
            } finally {
                if (execution != null && execution.getId() != null) {
                    activeTestRuns.remove(execution.getId());
                }
                budgetReference.set(null);
            }
        };
        Thread thread = Thread.ofVirtual().name("workflow-test-" + workflowId)
                .unstarted(new DelegatingSecurityContextRunnable(task, securityContext));
        Runnable disconnect = () -> {
            if (!finished.get()) {
                clientDisconnected.set(true);
                WorkflowExecutionBudget budget = budgetReference.get();
                if (budget != null) {
                    budget.cancel();
                }
                thread.interrupt();
            }
        };
        emitter.onTimeout(disconnect);
        emitter.onError(ignored -> disconnect.run());
        emitter.onCompletion(disconnect);
        thread.start();
    }

    public long testRunTimeoutMs() {
        return Math.multiplyExact((long) runtimeProperties.getMaxDurationSeconds(), 1_000L);
    }

    public void cancelTestRun(Long executionId) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        WorkflowExecution execution = executionRepository
                .findByIdAndTenant_Id(executionId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException(
                        "WORKFLOW_EXECUTION_NOT_FOUND", "Workflow execution not found", HttpStatus.NOT_FOUND));
        if (!"RUNNING".equals(execution.getStatus())) {
            throw new ApiException(
                    "WORKFLOW_EXECUTION_NOT_RUNNING", "Workflow execution is no longer running", HttpStatus.CONFLICT);
        }
        ActiveTestRun active = activeTestRuns.get(executionId);
        if (active == null || !active.tenantId().equals(currentUser.tenantId())) {
            throw new ApiException(
                    "WORKFLOW_EXECUTION_NOT_CANCELLABLE",
                    "Workflow execution is not active on this application instance",
                    HttpStatus.CONFLICT);
        }
        active.budget().cancel();
        active.thread().interrupt();
    }

    private WorkflowGraph resolveWorkflowGraph(Workflow workflow, SaveWorkflowCanvasRequest draftCanvas) {
        if (draftCanvas == null) {
            return new WorkflowGraph(
                    workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(workflow.getId()),
                    workflowEdgeRepository.findAllByWorkflow_Id(workflow.getId())
            );
        }

        List<WorkflowNode> nodes = new java.util.ArrayList<>();
        long syntheticId = -1L;
        for (int nodeIndex = 0; nodeIndex < draftCanvas.nodes().size(); nodeIndex++) {
            SaveWorkflowCanvasRequest.NodeItem item = draftCanvas.nodes().get(nodeIndex);
            WorkflowNode node = new WorkflowNode();
            node.setId(syntheticId--);
            node.setWorkflow(workflow);
            node.setNodeKey(item.nodeKey() == null || item.nodeKey().isBlank()
                    ? "node_" + nodeIndex
                    : item.nodeKey().trim());
            node.setName(item.name());
            node.setNodeType(WorkflowNodeType.valueOf(item.nodeType().trim().toUpperCase()));
            node.setConfigJson(item.configJson() == null || item.configJson().isBlank() ? "{}" : item.configJson());
            node.setPositionX(item.positionX());
            node.setPositionY(item.positionY());
            nodes.add(node);
        }

        List<WorkflowEdge> edges = new java.util.ArrayList<>();
        for (SaveWorkflowCanvasRequest.EdgeItem item : draftCanvas.edges()) {
            if (item.sourceNodeIndex() < 0 || item.sourceNodeIndex() >= nodes.size()
                    || item.targetNodeIndex() < 0 || item.targetNodeIndex() >= nodes.size()) {
                throw new ApiException(
                        "WORKFLOW_EDGE_INDEX_INVALID",
                        "Draft workflow edge references a missing node",
                        HttpStatus.BAD_REQUEST
                );
            }
            WorkflowEdge edge = new WorkflowEdge();
            edge.setWorkflow(workflow);
            edge.setSourceNode(nodes.get(item.sourceNodeIndex()));
            edge.setTargetNode(nodes.get(item.targetNodeIndex()));
            edge.setSourceHandle(item.sourceHandle());
            edge.setTargetHandle(item.targetHandle());
            edges.add(edge);
        }

        return new WorkflowGraph(nodes, edges);
    }

    PreparedModelConfig resolveModelConfigForTest(Long modelConfigId, Long tenantId) {
        ModelConfig mc = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException("MODEL_CONFIG_NOT_FOUND", "Model config not found", HttpStatus.BAD_REQUEST));
        if (!mc.isEnabled() || mc.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_INVALID", "Model config must be an enabled chat model", HttpStatus.BAD_REQUEST);
        }
        CredentialSource credentialSource = mc.getCredentialSource() == ModelCredentialSource.USER
                || (mc.getCredentialSource() == null && mc.getCredential() != null)
                ? CredentialSource.USER : CredentialSource.PLATFORM;
        if (credentialSource == CredentialSource.PLATFORM) {
            throw new ApiException(
                    "WORKFLOW_TEST_PLATFORM_MODEL_FORBIDDEN",
                    "Workflow test-run cannot use platform-funded model credentials",
                    HttpStatus.FORBIDDEN
            );
        }
        return new PreparedModelConfig(
                mc.getProvider() != null ? mc.getProvider() : CredentialProvider.OPENAI,
                ModelType.CHAT,
                mc.getModelName(),
                credentialSource,
                mc.getCredential() == null ? null : mc.getCredential().getId(),
                mc.getCredential() == null ? null : mc.getCredential().getSecretCiphertext(),
                mc.getTemperature(),
                mc.getMaxTokens(),
                null, null, null, null, null
        );
    }

    private Long resolveAgentIdForTest(Workflow workflow) {
        return agentRepository.findFirstByWorkflow_IdAndTenant_IdOrderByIdAsc(
                        workflow.getId(),
                        workflow.getTenant().getId()
                )
                .map(Agent::getId)
                .orElse(null);
    }

    private void sendErrorAndComplete(SseEmitter emitter, String code, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("code", code, "message", message)));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    private void sendEventOrCancel(
            SseEmitter emitter,
            WorkflowExecutionBudget budget,
            String eventName,
            Object data
    ) {
        budget.checkActive();
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception ex) {
            budget.cancel();
            throw new ApiException(
                    "WORKFLOW_CLIENT_DISCONNECTED",
                    "Workflow event stream was disconnected",
                    HttpStatus.CONFLICT
            );
        }
    }

    private String extractFinalOutput(Map<String, Object> context) {
        Object output = context.get("final_output");
        if (output != null) {
            return output.toString();
        }
        Object llmOutput = context.get("llm_output");
        if (llmOutput != null) {
            return llmOutput.toString();
        }
        return "";
    }

    private ModelGatewayResult buildResult(
            Map<String, Object> context,
            String content,
            PreparedChatTurn turn,
            WorkflowExecutionBudget executionBudget
    ) {
        int promptTokens = executionBudget.promptTokens();
        int completionTokens = executionBudget.completionTokens();
        int totalTokens = promptTokens + completionTokens;

        return new ModelGatewayResult(
                content,
                turn.modelConfig().provider(),
                turn.modelConfig().modelName(),
                resolveCredentialSource(turn.modelConfig()),
                turn.modelConfig().credentialId(),
                0L,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private CredentialSource resolveCredentialSource(com.enjoy.agent.modelgateway.application.PreparedModelConfig modelConfig) {
        return modelConfig.credentialSource();
    }

    private Map<String, WorkflowNode> indexWorkflowNodes(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> byKey = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            byKey.put(node.getNodeKey(), node);
        }
        return byKey;
    }

    private WorkflowExecution beginExecution(Workflow workflow, String input, String triggerType, int nodeCount, Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Tenant not found", HttpStatus.FORBIDDEN));
        WorkflowExecution exec = new WorkflowExecution();
        exec.setWorkflow(workflow);
        exec.setTenant(tenant);
        exec.setTriggerType(triggerType);
        exec.setStatus("RUNNING");
        exec.setInputJson(outputSanitizer.sanitizeText(input));
        exec.setNodeCount(nodeCount);
        exec.setCreatedAt(Instant.now());
        return executionRepository.save(exec);
    }

    private void completeExecution(WorkflowExecution exec, String status, long startMs, String output, Map<String, Object> context) {
        exec.setStatus(status);
        exec.setDurationMs(System.currentTimeMillis() - startMs);
        exec.setCompletedAt(Instant.now());
        if (output != null) {
            exec.setOutputJson(outputSanitizer.sanitizeText(output));
        }
        if (context != null) {
            try {
                exec.setExecutedCount((int) context.values().stream().filter(v -> v != null).count());
            } catch (Exception ignored) {
            }
        }
        executionRepository.save(exec);
    }

    private String truncate(String value, int maxLen) {
        return value != null && value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    private record WorkflowGraph(
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges
    ) {
    }

    private record ActiveTestRun(
            Long tenantId,
            WorkflowExecutionBudget budget,
            Thread thread
    ) {
    }
}
