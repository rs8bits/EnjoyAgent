package com.enjoy.agent.workflow.api;

import com.enjoy.agent.shared.api.ApiResponse;
import com.enjoy.agent.shared.api.PagedResponse;
import com.enjoy.agent.workflow.api.request.CreateWorkflowRequest;
import com.enjoy.agent.workflow.api.request.SaveWorkflowCanvasRequest;
import com.enjoy.agent.workflow.api.request.UpdateWorkflowRequest;
import com.enjoy.agent.workflow.api.response.WorkflowCanvasResponse;
import com.enjoy.agent.workflow.api.response.WorkflowResponse;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.workflow.application.WorkflowApplicationService;
import com.enjoy.agent.workflow.application.WorkflowOrchestratorService;
import com.enjoy.agent.workflow.infrastructure.persistence.WorkflowExecutionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "工作流管理", description = "工作流查看和画布编辑")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowApplicationService workflowApplicationService;
    private final WorkflowOrchestratorService workflowOrchestratorService;
    private final WorkflowExecutionRepository executionRepository;

    public WorkflowController(
            WorkflowApplicationService workflowApplicationService,
            WorkflowOrchestratorService workflowOrchestratorService,
            WorkflowExecutionRepository executionRepository
    ) {
        this.workflowApplicationService = workflowApplicationService;
        this.workflowOrchestratorService = workflowOrchestratorService;
        this.executionRepository = executionRepository;
    }

    @Operation(summary = "获取工作流列表")
    @GetMapping
    public ApiResponse<PagedResponse<WorkflowResponse>> listWorkflows(Pageable pageable) {
        Page<WorkflowResponse> page = workflowApplicationService.listWorkflows(pageable);
        return ApiResponse.success(PagedResponse.of(page));
    }

    @Operation(summary = "创建工作流")
    @PostMapping
    public ApiResponse<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        return ApiResponse.success(workflowApplicationService.createWorkflow(request), "工作流创建成功");
    }

    @Operation(summary = "获取工作流详情")
    @GetMapping("/{id}")
    public ApiResponse<WorkflowResponse> getWorkflow(@PathVariable Long id) {
        return ApiResponse.success(workflowApplicationService.getWorkflow(id));
    }

    @Operation(summary = "更新工作流")
    @PutMapping("/{id}")
    public ApiResponse<WorkflowResponse> updateWorkflow(@PathVariable Long id, @Valid @RequestBody UpdateWorkflowRequest request) {
        return ApiResponse.success(workflowApplicationService.updateWorkflow(id, request), "工作流更新成功");
    }

    @Operation(summary = "删除工作流")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteWorkflow(@PathVariable Long id) {
        workflowApplicationService.deleteWorkflow(id);
        return ApiResponse.success(null, "工作流已删除");
    }

    @Operation(summary = "加载画布数据")
    @GetMapping("/{id}/canvas")
    public ApiResponse<WorkflowCanvasResponse> getCanvas(@PathVariable Long id) {
        return ApiResponse.success(workflowApplicationService.getCanvas(id));
    }

    @Operation(summary = "保存画布数据")
    @PutMapping("/{id}/canvas")
    public ApiResponse<WorkflowCanvasResponse> saveCanvas(
            @PathVariable Long id,
            @Valid @RequestBody SaveWorkflowCanvasRequest request
    ) {
        return ApiResponse.success(workflowApplicationService.saveCanvas(id, request), "画布保存成功");
    }

    @Operation(summary = "测试运行工作流", description = "以 SSE 流式返回每个节点的执行结果")
    @PostMapping("/{id}/test-run")
    public SseEmitter testRun(
            @PathVariable Long id,
            @Valid @RequestBody TestRunRequest request
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        workflowOrchestratorService.testRun(id, request.userInput(), request.modelConfigId(), request.canvas(), emitter);
        return emitter;
    }

    @Operation(summary = "获取工作流执行历史")
    @GetMapping("/{id}/executions")
    public ApiResponse<PagedResponse<Map<String, Object>>> listExecutions(
            @PathVariable Long id, Pageable pageable) {
        Long tenantId = CurrentUserContext.requireCurrentUser().tenantId();
        var page = executionRepository
                .findAllByWorkflow_IdAndTenant_IdOrderByCreatedAtDesc(id, tenantId, pageable)
                .map(exe -> Map.<String, Object>of(
                        "id", exe.getId(),
                        "triggerType", exe.getTriggerType(),
                        "status", exe.getStatus(),
                        "inputJson", exe.getInputJson() != null ? exe.getInputJson() : "",
                        "outputJson", exe.getOutputJson() != null ? exe.getOutputJson() : "",
                        "nodeCount", exe.getNodeCount(),
                        "executedCount", exe.getExecutedCount(),
                        "durationMs", exe.getDurationMs() != null ? exe.getDurationMs() : 0,
                        "errorMessage", exe.getErrorMessage() != null ? exe.getErrorMessage() : "",
                        "createdAt", exe.getCreatedAt() != null ? exe.getCreatedAt().toString() : ""
                ));
        return ApiResponse.success(PagedResponse.of(page));
    }

    private record TestRunRequest(
            @NotBlank String userInput,
            @NotNull Long modelConfigId,
            SaveWorkflowCanvasRequest canvas
    ) {
    }
}
