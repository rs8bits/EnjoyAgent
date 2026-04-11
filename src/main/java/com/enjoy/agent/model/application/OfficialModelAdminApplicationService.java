package com.enjoy.agent.model.application;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.api.request.CreateOfficialModelConfigRequest;
import com.enjoy.agent.model.api.request.CreateOfficialModelCredentialRequest;
import com.enjoy.agent.model.api.request.UpdateOfficialModelConfigRequest;
import com.enjoy.agent.model.api.request.UpdateOfficialModelCredentialRequest;
import com.enjoy.agent.model.api.response.OfficialModelConfigResponse;
import com.enjoy.agent.model.api.response.OfficialModelCredentialResponse;
import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.entity.OfficialModelCredential;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelCredentialRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialModelAdminApplicationService {

    private final OfficialModelCredentialRepository officialModelCredentialRepository;
    private final OfficialModelConfigRepository officialModelConfigRepository;
    private final AesCryptoService aesCryptoService;

    public OfficialModelAdminApplicationService(
            OfficialModelCredentialRepository officialModelCredentialRepository,
            OfficialModelConfigRepository officialModelConfigRepository,
            AesCryptoService aesCryptoService
    ) {
        this.officialModelCredentialRepository = officialModelCredentialRepository;
        this.officialModelConfigRepository = officialModelConfigRepository;
        this.aesCryptoService = aesCryptoService;
    }

    @Transactional
    public OfficialModelCredentialResponse createCredential(CreateOfficialModelCredentialRequest request) {
        requireAdmin();
        String normalizedName = normalizeName(request.name());
        if (officialModelCredentialRepository.existsByName(normalizedName)) {
            throw new ApiException("OFFICIAL_MODEL_CREDENTIAL_NAME_DUPLICATED", "Official model credential name already exists", HttpStatus.CONFLICT);
        }
        validateProvider(request.provider());

        OfficialModelCredential credential = new OfficialModelCredential();
        credential.setName(normalizedName);
        credential.setProvider(request.provider());
        credential.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        credential.setSecretCiphertext(aesCryptoService.encrypt(request.secretPlaintext().trim()));
        credential.setEnabled(request.enabled() == null || request.enabled());
        return toCredentialResponse(officialModelCredentialRepository.saveAndFlush(credential));
    }

    @Transactional(readOnly = true)
    public List<OfficialModelCredentialResponse> listCredentials() {
        requireAdmin();
        return officialModelCredentialRepository.findAllByOrderByIdDesc().stream()
                .map(this::toCredentialResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OfficialModelCredentialResponse getCredential(Long id) {
        requireAdmin();
        return toCredentialResponse(requireCredential(id));
    }

    @Transactional
    public OfficialModelCredentialResponse updateCredential(Long id, UpdateOfficialModelCredentialRequest request) {
        requireAdmin();
        OfficialModelCredential credential = requireCredential(id);
        String normalizedName = normalizeName(request.name());
        if (officialModelCredentialRepository.existsByNameAndIdNot(normalizedName, id)) {
            throw new ApiException("OFFICIAL_MODEL_CREDENTIAL_NAME_DUPLICATED", "Official model credential name already exists", HttpStatus.CONFLICT);
        }
        validateProvider(request.provider());

        credential.setName(normalizedName);
        credential.setProvider(request.provider());
        credential.setBaseUrl(normalizeBaseUrl(request.baseUrl()));
        if (request.secretPlaintext() != null && !request.secretPlaintext().isBlank()) {
            credential.setSecretCiphertext(aesCryptoService.encrypt(request.secretPlaintext().trim()));
        }
        credential.setEnabled(request.enabled());
        return toCredentialResponse(officialModelCredentialRepository.saveAndFlush(credential));
    }

    @Transactional
    public void deleteCredential(Long id) {
        requireAdmin();
        officialModelCredentialRepository.delete(requireCredential(id));
    }

    @Transactional
    public OfficialModelConfigResponse createConfig(CreateOfficialModelConfigRequest request) {
        requireAdmin();
        String normalizedName = normalizeName(request.name());
        if (officialModelConfigRepository.existsByName(normalizedName)) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_NAME_DUPLICATED", "Official model config name already exists", HttpStatus.CONFLICT);
        }

        OfficialModelCredential credential = requireEnabledCredential(request.officialCredentialId());
        validateOfficialModelRequest(request.provider(), request.modelType(), credential.getProvider());

        OfficialModelConfig config = new OfficialModelConfig();
        config.setName(normalizedName);
        config.setProvider(request.provider());
        config.setModelType(request.modelType());
        config.setModelName(normalizeModelName(request.modelName()));
        config.setOfficialCredential(credential);
        config.setTemperature(request.temperature());
        config.setMaxTokens(request.maxTokens());
        config.setInputPricePerMillion(request.inputPricePerMillion());
        config.setOutputPricePerMillion(request.outputPricePerMillion());
        config.setCurrency(normalizeCurrency(request.currency()));
        config.setDescription(normalizeDescription(request.description()));
        config.setEnabled(request.enabled() == null || request.enabled());
        return toConfigResponse(officialModelConfigRepository.saveAndFlush(config));
    }

    @Transactional(readOnly = true)
    public List<OfficialModelConfigResponse> listConfigs() {
        requireAdmin();
        return officialModelConfigRepository.findAllByOrderByIdDesc().stream()
                .map(this::toConfigResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OfficialModelConfigResponse getConfig(Long id) {
        requireAdmin();
        return toConfigResponse(requireConfig(id));
    }

    @Transactional
    public OfficialModelConfigResponse updateConfig(Long id, UpdateOfficialModelConfigRequest request) {
        requireAdmin();
        OfficialModelConfig config = requireConfig(id);
        String normalizedName = normalizeName(request.name());
        if (officialModelConfigRepository.existsByNameAndIdNot(normalizedName, id)) {
            throw new ApiException("OFFICIAL_MODEL_CONFIG_NAME_DUPLICATED", "Official model config name already exists", HttpStatus.CONFLICT);
        }

        OfficialModelCredential credential = requireEnabledCredential(request.officialCredentialId());
        validateOfficialModelRequest(request.provider(), request.modelType(), credential.getProvider());

        config.setName(normalizedName);
        config.setProvider(request.provider());
        config.setModelType(request.modelType());
        config.setModelName(normalizeModelName(request.modelName()));
        config.setOfficialCredential(credential);
        config.setTemperature(request.temperature());
        config.setMaxTokens(request.maxTokens());
        config.setInputPricePerMillion(request.inputPricePerMillion());
        config.setOutputPricePerMillion(request.outputPricePerMillion());
        config.setCurrency(normalizeCurrency(request.currency()));
        config.setDescription(normalizeDescription(request.description()));
        config.setEnabled(request.enabled());
        return toConfigResponse(officialModelConfigRepository.saveAndFlush(config));
    }

    @Transactional
    public void deleteConfig(Long id) {
        requireAdmin();
        officialModelConfigRepository.delete(requireConfig(id));
    }

    @Transactional(readOnly = true)
    public List<OfficialModelConfigResponse> listEnabledConfigsForUsers(ModelType modelType) {
        List<OfficialModelConfig> configs = modelType == null
                ? officialModelConfigRepository.findAllByEnabledTrueOrderByIdDesc()
                : officialModelConfigRepository.findAllByEnabledTrueAndModelTypeOrderByIdDesc(modelType);
        return configs.stream()
                .map(this::toConfigResponse)
                .toList();
    }

    private void requireAdmin() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        if (currentUser.systemRole() != SystemRole.ADMIN) {
            throw new ApiException("FORBIDDEN", "Admin permission required", HttpStatus.FORBIDDEN);
        }
    }

    private OfficialModelCredential requireCredential(Long id) {
        return officialModelCredentialRepository.findById(id)
                .orElseThrow(() -> new ApiException("OFFICIAL_MODEL_CREDENTIAL_NOT_FOUND", "Official model credential not found", HttpStatus.NOT_FOUND));
    }

    private OfficialModelCredential requireEnabledCredential(Long id) {
        OfficialModelCredential credential = requireCredential(id);
        if (!credential.isEnabled()) {
            throw new ApiException("OFFICIAL_MODEL_CREDENTIAL_DISABLED", "Official model credential is disabled", HttpStatus.BAD_REQUEST);
        }
        return credential;
    }

    private OfficialModelConfig requireConfig(Long id) {
        return officialModelConfigRepository.findById(id)
                .orElseThrow(() -> new ApiException("OFFICIAL_MODEL_CONFIG_NOT_FOUND", "Official model config not found", HttpStatus.NOT_FOUND));
    }

    private void validateProvider(CredentialProvider provider) {
        if (!provider.isOpenAiCompatibleRuntime()) {
            throw new ApiException("MODEL_PROVIDER_NOT_SUPPORTED", "Current runtime only supports OpenAI-compatible providers", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateOfficialModelRequest(
            CredentialProvider provider,
            ModelType modelType,
            CredentialProvider credentialProvider
    ) {
        validateProvider(provider);
        if (provider != credentialProvider) {
            throw new ApiException("OFFICIAL_MODEL_PROVIDER_MISMATCH", "Official model provider does not match official credential provider", HttpStatus.BAD_REQUEST);
        }
        if (modelType != ModelType.CHAT && modelType != ModelType.EMBEDDING && modelType != ModelType.RERANK) {
            throw new ApiException("MODEL_CONFIG_TYPE_INVALID", "Unsupported official model type", HttpStatus.BAD_REQUEST);
        }
    }

    private OfficialModelCredentialResponse toCredentialResponse(OfficialModelCredential credential) {
        return new OfficialModelCredentialResponse(
                credential.getId(),
                credential.getName(),
                credential.getProvider().name(),
                credential.getBaseUrl(),
                credential.isEnabled(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }

    private OfficialModelConfigResponse toConfigResponse(OfficialModelConfig config) {
        return new OfficialModelConfigResponse(
                config.getId(),
                config.getName(),
                config.getProvider().name(),
                config.getModelType().name(),
                config.getModelName(),
                config.getOfficialCredential().getId(),
                config.getOfficialCredential().getName(),
                config.getTemperature(),
                config.getMaxTokens(),
                config.getInputPricePerMillion(),
                config.getOutputPricePerMillion(),
                config.getCurrency(),
                config.getDescription(),
                config.isEnabled(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private String normalizeName(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeModelName(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeCurrency(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String normalizeBaseUrl(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/v1/chat/completions".length());
        } else if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        } else if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - "/v1".length());
        }
        return normalized;
    }
}
