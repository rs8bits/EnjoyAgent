package com.enjoy.agent.model.api;

import com.enjoy.agent.model.api.request.CreateModelConfigRequest;
import com.enjoy.agent.model.api.request.UpdateModelConfigRequest;
import com.enjoy.agent.model.api.response.ModelConfigResponse;
import com.enjoy.agent.model.application.ModelConfigApplicationService;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置管理接口。
 */
@Tag(name = "模型配置", description = "管理当前租户下可复用的模型配置")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/model-configs")
public class ModelConfigController {

    private final ModelConfigApplicationService modelConfigApplicationService;

    public ModelConfigController(ModelConfigApplicationService modelConfigApplicationService) {
        this.modelConfigApplicationService = modelConfigApplicationService;
    }

    /**
     * 创建模型配置。
     */
    @Operation(summary = "创建模型配置", description = "在当前租户下创建一份可供 Agent 复用的模型配置")
    @PostMapping
    public ApiResponse<ModelConfigResponse> createModelConfig(@Valid @RequestBody CreateModelConfigRequest request) {
        return ApiResponse.success(modelConfigApplicationService.createModelConfig(request), "Model config created");
    }

    /**
     * 查询当前租户的模型配置列表。
     */
    @Operation(summary = "模型配置列表", description = "查询当前租户下的模型配置")
    @GetMapping
    public ApiResponse<List<ModelConfigResponse>> listModelConfigs() {
        return ApiResponse.success(modelConfigApplicationService.listModelConfigs());
    }

    /**
     * 查询当前租户内的单个模型配置。
     */
    @Operation(summary = "模型配置详情", description = "按 ID 查询当前租户下的模型配置")
    @GetMapping("/{id}")
    public ApiResponse<ModelConfigResponse> getModelConfig(@PathVariable Long id) {
        return ApiResponse.success(modelConfigApplicationService.getModelConfig(id));
    }

    /**
     * 更新模型配置。
     */
    @Operation(summary = "更新模型配置", description = "更新当前租户下的模型配置，包括推荐模型参数和绑定凭证")
    @PutMapping("/{id}")
    public ApiResponse<ModelConfigResponse> updateModelConfig(
            @PathVariable Long id,
            @Valid @RequestBody UpdateModelConfigRequest request
    ) {
        return ApiResponse.success(modelConfigApplicationService.updateModelConfig(id, request), "Model config updated");
    }

    /**
     * 删除模型配置。
     */
    @Operation(summary = "删除模型配置", description = "删除当前租户下的模型配置")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteModelConfig(@PathVariable Long id) {
        modelConfigApplicationService.deleteModelConfig(id);
        return ApiResponse.success(null, "Model config deleted");
    }
}
