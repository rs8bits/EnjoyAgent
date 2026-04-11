package com.enjoy.agent.model.api;

import com.enjoy.agent.model.api.response.OfficialModelConfigResponse;
import com.enjoy.agent.model.application.OfficialModelAdminApplicationService;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.shared.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "官方模型", description = "查询平台可用的官方模型配置")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/official-model-configs")
public class OfficialModelController {

    private final OfficialModelAdminApplicationService officialModelAdminApplicationService;

    public OfficialModelController(OfficialModelAdminApplicationService officialModelAdminApplicationService) {
        this.officialModelAdminApplicationService = officialModelAdminApplicationService;
    }

    @Operation(summary = "官方模型列表")
    @GetMapping
    public ApiResponse<List<OfficialModelConfigResponse>> listEnabledConfigs(
            @RequestParam(required = false) ModelType modelType
    ) {
        return ApiResponse.success(officialModelAdminApplicationService.listEnabledConfigsForUsers(modelType));
    }
}
