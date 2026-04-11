package com.enjoy.agent.credential.application;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository;
import com.enjoy.agent.credential.api.request.CreateCredentialRequest;
import com.enjoy.agent.credential.api.request.UpdateCredentialRequest;
import com.enjoy.agent.credential.api.response.CredentialResponse;
import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.domain.enums.CredentialType;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.crypto.CredentialMaskingUtils;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.CurrentUserContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 凭证应用服务。
 */
@Service
public class CredentialApplicationService {

    private final CredentialRepository credentialRepository;
    private final AppUserRepository appUserRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final AesCryptoService aesCryptoService;

    public CredentialApplicationService(
            CredentialRepository credentialRepository,
            AppUserRepository appUserRepository,
            ModelConfigRepository modelConfigRepository,
            AesCryptoService aesCryptoService
    ) {
        this.credentialRepository = credentialRepository;
        this.appUserRepository = appUserRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.aesCryptoService = aesCryptoService;
    }

    /**
     * 创建当前用户的凭证。
     */
    @Transactional
    public CredentialResponse createCredential(CreateCredentialRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        String normalizedName = normalizeName(request.name());

        if (credentialRepository.existsByUser_IdAndName(currentUser.userId(), normalizedName)) {
            throw new ApiException("CREDENTIAL_NAME_DUPLICATED", "Credential name already exists", HttpStatus.CONFLICT);
        }

        AppUser user = appUserRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "Current user not found", HttpStatus.UNAUTHORIZED));

        Credential credential = new Credential();
        credential.setUser(user);
        credential.setName(normalizedName);
        credential.setProvider(request.provider());
        credential.setCredentialType(CredentialType.API_KEY);
        credential.setSecretCiphertext(aesCryptoService.encrypt(request.secret().trim()));
        credential.setSecretMasked(CredentialMaskingUtils.mask(request.secret().trim()));
        credential.setBaseUrl(normalizeBaseUrl(request.provider(), request.baseUrl()));
        credential.setDescription(normalizeDescription(request.description()));
        credential.setStatus(CredentialStatus.ACTIVE);

        Credential savedCredential = credentialRepository.saveAndFlush(credential);
        return toResponse(savedCredential);
    }

    /**
     * 查询当前用户的全部凭证。
     */
    @Transactional(readOnly = true)
    public List<CredentialResponse> listCredentials() {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return credentialRepository.findAllByUser_IdOrderByIdDesc(currentUser.userId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询当前用户的单个凭证。
     */
    @Transactional(readOnly = true)
    public CredentialResponse getCredential(Long id) {
        return toResponse(requireOwnedCredential(id));
    }

    /**
     * 更新当前用户的凭证。
     */
    @Transactional
    public CredentialResponse updateCredential(Long id, UpdateCredentialRequest request) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        Credential credential = requireOwnedCredential(id);
        String normalizedName = normalizeName(request.name());

        if (credentialRepository.existsByUser_IdAndNameAndIdNot(currentUser.userId(), normalizedName, id)) {
            throw new ApiException("CREDENTIAL_NAME_DUPLICATED", "Credential name already exists", HttpStatus.CONFLICT);
        }

        credential.setName(normalizedName);
        credential.setBaseUrl(normalizeBaseUrl(credential.getProvider(), request.baseUrl()));
        credential.setDescription(normalizeDescription(request.description()));
        credential.setStatus(request.status() == null ? credential.getStatus() : request.status());

        if (request.secret() != null && !request.secret().isBlank()) {
            String normalizedSecret = request.secret().trim();
            credential.setSecretCiphertext(aesCryptoService.encrypt(normalizedSecret));
            credential.setSecretMasked(CredentialMaskingUtils.mask(normalizedSecret));
        }

        Credential savedCredential = credentialRepository.saveAndFlush(credential);
        return toResponse(savedCredential);
    }

    /**
     * 删除当前用户的凭证。
     */
    @Transactional
    public void deleteCredential(Long id) {
        Credential credential = requireOwnedCredential(id);
        if (modelConfigRepository.existsByCredential_Id(credential.getId())) {
            throw new ApiException(
                    "CREDENTIAL_IN_USE",
                    "Credential is referenced by model configs and cannot be deleted",
                    HttpStatus.CONFLICT
            );
        }
        credentialRepository.delete(credential);
    }

    /**
     * 确保当前用户只能访问自己的凭证。
     */
    private Credential requireOwnedCredential(Long id) {
        AuthenticatedUser currentUser = CurrentUserContext.requireCurrentUser();
        return credentialRepository.findByIdAndUser_Id(id, currentUser.userId())
                .orElseThrow(() -> new ApiException("CREDENTIAL_NOT_FOUND", "Credential not found", HttpStatus.NOT_FOUND));
    }

    /**
     * 转成接口返回对象。
     */
    private CredentialResponse toResponse(Credential credential) {
        return new CredentialResponse(
                credential.getId(),
                credential.getName(),
                credential.getProvider().name(),
                credential.getCredentialType().name(),
                credential.getSecretMasked(),
                credential.getBaseUrl(),
                credential.getDescription(),
                credential.getStatus().name(),
                credential.getCreatedAt(),
                credential.getUpdatedAt()
        );
    }

    /**
     * 统一处理名称空白。
     */
    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    /**
     * 统一处理可选备注。
     */
    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    private String normalizeBaseUrl(com.enjoy.agent.credential.domain.enums.CredentialProvider provider, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return provider == null ? null : provider.defaultCompatibleBaseUrl();
        }
        String normalized = baseUrl.trim();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new ApiException("CREDENTIAL_BASE_URL_INVALID", "Credential baseUrl must start with http:// or https://", HttpStatus.BAD_REQUEST);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
