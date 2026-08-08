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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
            AgentRepository agentRepository
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
    }

    public ModelGatewayResult executeWorkflow(PreparedChatTurn turn) {
        Workflow workflow = workflowRepository.findById(turn.workflowId())
                .orElseThrow(() -> new ApiException("WORKFLOW_NOT_FOUND", "Workflow not found", HttpStatus.INTERNAL_SERVER_ERROR));
        if (!workflow.isEnabled()) {
            throw new ApiException("WORKFLOW_DISABLED", "Workflow is disabled", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        List<WorkflowNode> nodes = workflowNodeRepository.findAllByWorkflow_IdOrderByIdAsc(workflow.getId());
        List<WorkflowEdge> edges = workflowEdgeRepository.findAllByWorkflow_Id(workflow.getId());
        dagExecutor.validate(nodes, edges);
        List<WorkflowNode> sortedNodes = dagExecutor.topologicalSort(nodes, edges);

        WorkflowExecution execution = beginExecution(workflow, turn.latestUserMessageContent(), "CHAT", sortedNodes.size());
        long startMs = System.currentTimeMillis();

        try {
            Map<String, Object> initialContext = new HashMap<>();
            initialContext.put("user_input", turn.latestUserMessageContent());
            initialContext.put("history_messages", turn.historyMessages());
            initialContext.put("system_prompt", turn.systemPrompt());
            if (turn.sessionMemory() != null && turn.sessionMemory().summary() != null) {
                initialContext.put("session_memory_summary", turn.sessionMemory().summary());
            }

            NodeExecutionContext baseCtx = new NodeExecutionContext(
                    turn.tenantId(), turn.userId(), turn.agentId(), turn.sessionId(),
                    sortedNodes.get(0), turn.modelConfig(), initialContext
            );

            Map<String, Object> finalContext = dagExecutor.execute(sortedNodes, edges, nodeExecutorRegistry, baseCtx);
            String finalOutput = extractFinalOutput(finalContext);
            completeExecution(execution, "COMPLETED", startMs, finalOutput, finalContext);
            return buildResult(finalContext, finalOutput, turn);
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

                Map<String, Object> initialContext = new HashMap<>();
                initialContext.put("user_input", userInput);
                initialContext.put("history_messages", List.of());
                initialContext.put("system_prompt", "");

                NodeExecutionContext baseCtx = new NodeExecutionContext(
                        workflow.getTenant().getId(),
                        currentUser.userId(),
                        resolveAgentIdForTest(workflow),
                        null,
                        sortedNodes.get(0),
                        modelConfig,
                        initialContext
                );

                Map<String, Object> finalContext = dagExecutor.execute(sortedNodes, edges, nodeExecutorRegistry, baseCtx, event -> {
                    try { emitter.send(SseEmitter.event().name(event.type()).data(event)); } catch (Exception ignored) {}
                });

                String finalOutput = extractFinalOutput(finalContext);
                completeExecution(execution, "COMPLETED", startMs, finalOutput, finalContext);
                emitter.send(SseEmitter.event().name("completed").data(Map.of(
                        "finalOutput", finalOutput, "totalNodes", totalNodes, "executedContext", (Object) finalContext
                )));
                emitter.complete();
            } catch (ApiException ex) {
                if (execution != null) { completeExecution(execution, "FAILED", startMs, null, null); execution.setErrorMessage(truncate(ex.getMessage(), 1000)); executionRepository.save(execution); }
                sendErrorAndComplete(emitter, ex.getCode(), ex.getMessage());
            } catch (Exception ex) {
                if (execution != null) { completeExecution(execution, "FAILED", startMs, null, null); execution.setErrorMessage(truncate(ex.getMessage(), 1000)); executionRepository.save(execution); }
                sendErrorAndComplete(emitter, "TEST_RUN_FAILED", ex.getMessage());
            }
        };
        Thread.ofVirtual().name("workflow-test-" + workflowId).start(task);
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
        for (SaveWorkflowCanvasRequest.NodeItem item : draftCanvas.nodes()) {
            WorkflowNode node = new WorkflowNode();
            node.setId(syntheticId--);
            node.setWorkflow(workflow);
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

    private PreparedModelConfig resolveModelConfigForTest(Long modelConfigId, Long tenantId) {
        ModelConfig mc = modelConfigRepository.findByIdAndTenant_Id(modelConfigId, tenantId)
                .orElseThrow(() -> new ApiException("MODEL_CONFIG_NOT_FOUND", "Model config not found", HttpStatus.BAD_REQUEST));
        if (!mc.isEnabled() || mc.getModelType() != ModelType.CHAT) {
            throw new ApiException("MODEL_CONFIG_INVALID", "Model config must be an enabled chat model", HttpStatus.BAD_REQUEST);
        }
        CredentialSource credentialSource = mc.getCredentialSource() == ModelCredentialSource.USER
                ? CredentialSource.USER : CredentialSource.PLATFORM;
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

    private ModelGatewayResult buildResult(Map<String, Object> context, String content, PreparedChatTurn turn) {
        int promptTokens = intFromContext(context, "llm_prompt_tokens");
        int completionTokens = intFromContext(context, "llm_completion_tokens");
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

    private int intFromContext(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private CredentialSource resolveCredentialSource(com.enjoy.agent.modelgateway.application.PreparedModelConfig modelConfig) {
        return modelConfig.credentialSource();
    }

    private WorkflowExecution beginExecution(Workflow workflow, String input, String triggerType, int nodeCount) {
        Long tenantId = CurrentUserContext.requireCurrentUser().tenantId();
        return beginExecution(workflow, input, triggerType, nodeCount, tenantId);
    }

    private WorkflowExecution beginExecution(Workflow workflow, String input, String triggerType, int nodeCount, Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Tenant not found", HttpStatus.FORBIDDEN));
        WorkflowExecution exec = new WorkflowExecution();
        exec.setWorkflow(workflow);
        exec.setTenant(tenant);
        exec.setTriggerType(triggerType);
        exec.setStatus("RUNNING");
        exec.setInputJson(input);
        exec.setNodeCount(nodeCount);
        exec.setCreatedAt(Instant.now());
        return executionRepository.save(exec);
    }

    private void completeExecution(WorkflowExecution exec, String status, long startMs, String output, Map<String, Object> context) {
        exec.setStatus(status);
        exec.setDurationMs(System.currentTimeMillis() - startMs);
        exec.setCompletedAt(Instant.now());
        if (output != null) {
            exec.setOutputJson(output);
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
}
