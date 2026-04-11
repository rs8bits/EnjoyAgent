package com.enjoy.agent.model.application;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.model.api.request.CreateModelConfigRequest;
import com.enjoy.agent.model.api.request.UpdateModelConfigRequest;
import com.enjoy.agent.model.api.response.ModelConfigResponse;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型配置应用服务。
 */
@Service
public class ModelConfigApplicationService {

    private final ModelConfigRepository modelConfigRepository;
    private final TenantRepository tenantRepository;
    private final CredentialRepository credentialRepository;

    public ModelConfigApplicationService(
            ModelConfigRepository modelConfigRepository,
            TenantRepository tenantRepository,
            CredentialRepository credentialRepository
    ) {
        this.modelConfigRepository = modelConfigRepository;
        this.tenantRepository = tenantRepository;
        this.credentialRepository = credentialRepository;
    }

    /**
     * 在当前租户下创建模型配置。
     */
    @Transactional
    public ModelConfigResponse createModelConfig(CreateModelConfigRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Long tenantId = currentUser.tenantId();
        String normalizedName = normalizeName(request.name());

        if (modelConfigRepository.existsByTenant_IdAndName(tenantId, normalizedName)) {
            throw new ApiException("MODEL_CONFIG_NAME_DUPLICATED", "Model config name already exists", HttpStatus.CONFLICT);
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ApiException("TENANT_NOT_FOUND", "Current tenant not found", HttpStatus.FORBIDDEN));

        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setTenant(tenant);
        modelConfig.setName(normalizedName);
        modelConfig.setProvider(request.provider());
        modelConfig.setModelType(request.modelType());
        modelConfig.setModelName(normalizeModelName(request.modelName()));
        ModelCredentialBinding credentialBinding = resolveCredentialBinding(
                currentUser.userId(),
                request.provider(),
                request.credentialSource(),
                request.credentialId()
        );
        modelConfig.setCredentialSource(credentialBinding.credentialSource());
        modelConfig.setCredential(credentialBinding.credential());
        modelConfig.setTemperature(request.temperature());
        modelConfig.setMaxTokens(request.maxTokens());
        modelConfig.setEnabled(request.enabled() == null || request.enabled());

        ModelConfig savedModelConfig = modelConfigRepository.saveAndFlush(modelConfig);
        return toResponse(savedModelConfig);
    }

    /**
     * 查询当前租户下的全部模型配置。
     */
    @Transactional(readOnly = true)
    public List<ModelConfigResponse> listModelConfigs() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return modelConfigRepository.findAllByTenant_IdOrderByIdDesc(currentUser.tenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询当前租户内的单个模型配置。
     */
    @Transactional(readOnly = true)
    public ModelConfigResponse getModelConfig(Long id) {
        return toResponse(requireTenantOwnedModelConfig(id));
    }

    /**
     * 更新当前租户内的模型配置。
     */
    @Transactional
    public ModelConfigResponse updateModelConfig(Long id, UpdateModelConfigRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        ModelConfig modelConfig = requireTenantOwnedModelConfig(id);
        String normalizedName = normalizeName(request.name());

        if (modelConfigRepository.existsByTenant_IdAndNameAndIdNot(currentUser.tenantId(), normalizedName, id)) {
            throw new ApiException("MODEL_CONFIG_NAME_DUPLICATED", "Model config name already exists", HttpStatus.CONFLICT);
        }

        modelConfig.setName(normalizedName);
        modelConfig.setProvider(request.provider());
        modelConfig.setModelType(request.modelType());
        modelConfig.setModelName(normalizeModelName(request.modelName()));
        ModelCredentialBinding credentialBinding = resolveCredentialBinding(
                currentUser.userId(),
                request.provider(),
                request.credentialSource(),
                request.credentialId()
        );
        modelConfig.setCredentialSource(credentialBinding.credentialSource());
        modelConfig.setCredential(credentialBinding.credential());
        modelConfig.setTemperature(request.temperature());
        modelConfig.setMaxTokens(request.maxTokens());
        modelConfig.setEnabled(request.enabled());

        ModelConfig savedModelConfig = modelConfigRepository.saveAndFlush(modelConfig);
        return toResponse(savedModelConfig);
    }

    /**
     * 删除当前租户下的模型配置。
     */
    @Transactional
    public void deleteModelConfig(Long id) {
        modelConfigRepository.delete(requireTenantOwnedModelConfig(id));
    }

    /**
     * 限制当前用户只能操作自己当前租户下的模型配置。
     */
    private ModelConfig requireTenantOwnedModelConfig(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return modelConfigRepository.findByIdAndTenant_Id(id, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("MODEL_CONFIG_NOT_FOUND", "Model config not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 如果传入了凭证 ID，就要求这把凭证属于当前用户。
     */
    private ModelCredentialBinding resolveCredentialBinding(
            Long userId,
            CredentialProvider provider,
            ModelCredentialSource credentialSource,
            Long credentialId
    ) {
        if (credentialSource == ModelCredentialSource.PLATFORM) {
            throw new ApiException(
                    "MODEL_CONFIG_PLATFORM_SOURCE_NOT_ALLOWED",
                    "User model config cannot use platform source; please bind an official model on the agent instead",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (credentialId == null) {
            throw new ApiException(
                    "MODEL_CONFIG_USER_SOURCE_REQUIRES_CREDENTIAL",
                    "User sourced model config must bind a credential",
                    HttpStatus.BAD_REQUEST
            );
        }
        Credential credential = credentialRepository.findByIdAndUser_Id(credentialId, userId)
                .orElseThrow(() -> new ApiException("CREDENTIAL_NOT_FOUND", "Credential not found for current user", HttpStatus.BAD_REQUEST));
        if (credential.getStatus() != CredentialStatus.ACTIVE) {
            throw new ApiException("CREDENTIAL_DISABLED", "Credential is disabled", HttpStatus.BAD_REQUEST);
        }
        if (credential.getProvider() != provider) {
            throw new ApiException("CREDENTIAL_PROVIDER_MISMATCH", "Credential provider does not match model provider", HttpStatus.BAD_REQUEST);
        }
        return new ModelCredentialBinding(ModelCredentialSource.USER, credential);
    }

    /**
     * 转成接口返回对象。
     */
    private ModelConfigResponse toResponse(ModelConfig modelConfig) {
        Credential credential = modelConfig.getCredential();
        return new ModelConfigResponse(
                modelConfig.getId(),
                modelConfig.getTenant().getId(),
                modelConfig.getName(),
                modelConfig.getProvider().name(),
                modelConfig.getModelType().name(),
                modelConfig.getModelName(),
                resolveResponseCredentialSource(modelConfig).name(),
                credential == null ? null : credential.getId(),
                credential == null ? null : credential.getName(),
                modelConfig.getTemperature(),
                modelConfig.getMaxTokens(),
                modelConfig.isEnabled(),
                modelConfig.getCreatedAt(),
                modelConfig.getUpdatedAt()
        );
    }

    /**
     * 统一处理名称空白。
     */
    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    /**
     * 统一处理模型名空白。
     */
    private String normalizeModelName(String modelName) {
        return modelName == null ? null : modelName.trim();
    }

    private ModelCredentialSource resolveResponseCredentialSource(ModelConfig modelConfig) {
        if (modelConfig.getCredentialSource() != null) {
            return modelConfig.getCredentialSource();
        }
        return modelConfig.getCredential() == null ? ModelCredentialSource.PLATFORM : ModelCredentialSource.USER;
    }

    private record ModelCredentialBinding(
            ModelCredentialSource credentialSource,
            Credential credential
    ) {
    }
}
