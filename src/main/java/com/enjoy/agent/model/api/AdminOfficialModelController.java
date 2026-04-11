package com.enjoy.agent.model.api;

import com.enjoy.agent.model.api.request.CreateOfficialModelConfigRequest;
import com.enjoy.agent.model.api.request.CreateOfficialModelCredentialRequest;
import com.enjoy.agent.model.api.request.UpdateOfficialModelConfigRequest;
import com.enjoy.agent.model.api.request.UpdateOfficialModelCredentialRequest;
import com.enjoy.agent.model.api.response.OfficialModelConfigResponse;
import com.enjoy.agent.model.api.response.OfficialModelCredentialResponse;
import com.enjoy.agent.model.application.OfficialModelAdminApplicationService;
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

@Tag(name = "管理端-官方模型", description = "管理官方模型凭证和官方模型配置")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/official-models")
public class AdminOfficialModelController {

    private final OfficialModelAdminApplicationService officialModelAdminApplicationService;

    public AdminOfficialModelController(OfficialModelAdminApplicationService officialModelAdminApplicationService) {
        this.officialModelAdminApplicationService = officialModelAdminApplicationService;
    }

    @Operation(summary = "创建官方模型凭证")
    @PostMapping("/credentials")
    public ApiResponse<OfficialModelCredentialResponse> createCredential(
            @Valid @RequestBody CreateOfficialModelCredentialRequest request
    ) {
        return ApiResponse.success(officialModelAdminApplicationService.createCredential(request), "Official model credential created");
    }

    @Operation(summary = "官方模型凭证列表")
    @GetMapping("/credentials")
    public ApiResponse<List<OfficialModelCredentialResponse>> listCredentials() {
        return ApiResponse.success(officialModelAdminApplicationService.listCredentials());
    }

    @Operation(summary = "官方模型凭证详情")
    @GetMapping("/credentials/{id}")
    public ApiResponse<OfficialModelCredentialResponse> getCredential(@PathVariable Long id) {
        return ApiResponse.success(officialModelAdminApplicationService.getCredential(id));
    }

    @Operation(summary = "更新官方模型凭证")
    @PutMapping("/credentials/{id}")
    public ApiResponse<OfficialModelCredentialResponse> updateCredential(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOfficialModelCredentialRequest request
    ) {
        return ApiResponse.success(officialModelAdminApplicationService.updateCredential(id, request), "Official model credential updated");
    }

    @Operation(summary = "删除官方模型凭证")
    @DeleteMapping("/credentials/{id}")
    public ApiResponse<Void> deleteCredential(@PathVariable Long id) {
        officialModelAdminApplicationService.deleteCredential(id);
        return ApiResponse.success(null, "Official model credential deleted");
    }

    @Operation(summary = "创建官方模型配置")
    @PostMapping("/configs")
    public ApiResponse<OfficialModelConfigResponse> createConfig(
            @Valid @RequestBody CreateOfficialModelConfigRequest request
    ) {
        return ApiResponse.success(officialModelAdminApplicationService.createConfig(request), "Official model config created");
    }

    @Operation(summary = "官方模型配置列表")
    @GetMapping("/configs")
    public ApiResponse<List<OfficialModelConfigResponse>> listConfigs() {
        return ApiResponse.success(officialModelAdminApplicationService.listConfigs());
    }

    @Operation(summary = "官方模型配置详情")
    @GetMapping("/configs/{id}")
    public ApiResponse<OfficialModelConfigResponse> getConfig(@PathVariable Long id) {
        return ApiResponse.success(officialModelAdminApplicationService.getConfig(id));
    }

    @Operation(summary = "更新官方模型配置")
    @PutMapping("/configs/{id}")
    public ApiResponse<OfficialModelConfigResponse> updateConfig(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOfficialModelConfigRequest request
    ) {
        return ApiResponse.success(officialModelAdminApplicationService.updateConfig(id, request), "Official model config updated");
    }

    @Operation(summary = "删除官方模型配置")
    @DeleteMapping("/configs/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable Long id) {
        officialModelAdminApplicationService.deleteConfig(id);
        return ApiResponse.success(null, "Official model config deleted");
    }
}
