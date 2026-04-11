package com.enjoy.agent.credential.api;

import com.enjoy.agent.credential.api.request.CreateCredentialRequest;
import com.enjoy.agent.credential.api.request.UpdateCredentialRequest;
import com.enjoy.agent.credential.api.response.CredentialResponse;
import com.enjoy.agent.credential.application.CredentialApplicationService;
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
 * 用户凭证管理接口。
 */
@Tag(name = "凭证", description = "管理当前登录用户自己的模型 API Key")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialApplicationService credentialApplicationService;

    public CredentialController(CredentialApplicationService credentialApplicationService) {
        this.credentialApplicationService = credentialApplicationService;
    }

    /**
     * 创建一个新的用户凭证。
     */
    @Operation(summary = "创建凭证", description = "保存当前用户自己的模型 API Key，接口只返回脱敏值")
    @PostMapping
    public ApiResponse<CredentialResponse> createCredential(@Valid @RequestBody CreateCredentialRequest request) {
        return ApiResponse.success(credentialApplicationService.createCredential(request), "Credential created");
    }

    /**
     * 返回当前用户的凭证列表。
     */
    @Operation(summary = "凭证列表", description = "查询当前用户自己创建的凭证")
    @GetMapping
    public ApiResponse<List<CredentialResponse>> listCredentials() {
        return ApiResponse.success(credentialApplicationService.listCredentials());
    }

    /**
     * 按 ID 返回当前用户的单个凭证。
     */
    @Operation(summary = "凭证详情", description = "按 ID 查询当前用户自己的凭证详情")
    @GetMapping("/{id}")
    public ApiResponse<CredentialResponse> getCredential(@PathVariable Long id) {
        return ApiResponse.success(credentialApplicationService.getCredential(id));
    }

    /**
     * 更新凭证名称、描述、状态或密钥本身。
     */
    @Operation(summary = "更新凭证", description = "可更新名称、描述、状态，必要时也可以替换密钥")
    @PutMapping("/{id}")
    public ApiResponse<CredentialResponse> updateCredential(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCredentialRequest request
    ) {
        return ApiResponse.success(credentialApplicationService.updateCredential(id, request), "Credential updated");
    }

    /**
     * 删除当前用户自己的凭证。
     */
    @Operation(summary = "删除凭证", description = "删除当前用户自己的凭证")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCredential(@PathVariable Long id) {
        credentialApplicationService.deleteCredential(id);
        return ApiResponse.success(null, "Credential deleted");
    }
}
