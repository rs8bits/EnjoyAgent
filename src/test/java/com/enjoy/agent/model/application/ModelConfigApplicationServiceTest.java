package com.enjoy.agent.model.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.entity.Credential;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.model.api.request.CreateModelConfigRequest;
import com.enjoy.agent.model.domain.entity.ModelConfig;
import com.enjoy.agent.model.domain.enums.ModelCredentialSource;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.ModelConfigRepository;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.enjoy.agent.tenant.infrastructure.persistence.TenantRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ModelConfigApplicationServiceTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private CredentialRepository credentialRepository;

    private ModelConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ModelConfigApplicationService(modelConfigRepository, tenantRepository, credentialRepository);
        AuthenticatedUser currentUser = new AuthenticatedUser(
                11L,
                "tester@example.com",
                "tester",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUser, null, currentUser.authorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createModelConfig_shouldRejectUserSourceWithoutCredential() {
        when(modelConfigRepository.existsByTenant_IdAndName(7L, "默认对话")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));

        assertThatThrownBy(() -> service.createModelConfig(new CreateModelConfigRequest(
                "默认对话",
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                ModelCredentialSource.USER,
                null,
                BigDecimal.valueOf(0.2),
                2048,
                true
        )))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MODEL_CONFIG_USER_SOURCE_REQUIRES_CREDENTIAL");
    }

    @Test
    void createModelConfig_shouldRejectPlatformSource() {
        when(modelConfigRepository.existsByTenant_IdAndName(7L, "默认对话")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));

        assertThatThrownBy(() -> service.createModelConfig(new CreateModelConfigRequest(
                "默认对话",
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                ModelCredentialSource.PLATFORM,
                3L,
                BigDecimal.valueOf(0.2),
                2048,
                true
        )))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MODEL_CONFIG_PLATFORM_SOURCE_NOT_ALLOWED");
    }

    @Test
    void createModelConfig_shouldPersistUserCredentialBinding() {
        when(modelConfigRepository.existsByTenant_IdAndName(7L, "默认对话")).thenReturn(false);
        when(tenantRepository.findById(7L)).thenReturn(Optional.of(activeTenant()));
        when(credentialRepository.findByIdAndUser_Id(5L, 11L)).thenReturn(Optional.of(activeCredential(5L)));
        when(modelConfigRepository.saveAndFlush(any(ModelConfig.class))).thenAnswer(invocation -> {
            ModelConfig modelConfig = invocation.getArgument(0);
            modelConfig.setId(102L);
            return modelConfig;
        });

        var response = service.createModelConfig(new CreateModelConfigRequest(
                "默认对话",
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                ModelCredentialSource.USER,
                5L,
                BigDecimal.valueOf(0.2),
                2048,
                true
        ));

        assertThat(response.id()).isEqualTo(102L);
        assertThat(response.credentialSource()).isEqualTo("USER");
        assertThat(response.credentialId()).isEqualTo(5L);
        assertThat(response.credentialName()).isEqualTo("我的百炼");
    }

    private Tenant activeTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setCode("tenant-7");
        tenant.setName("Tenant 7");
        return tenant;
    }

    private Credential activeCredential(Long id) {
        Credential credential = new Credential();
        credential.setId(id);
        credential.setName("我的百炼");
        credential.setProvider(CredentialProvider.OPENAI);
        credential.setStatus(CredentialStatus.ACTIVE);
        credential.setSecretCiphertext("cipher");
        return credential;
    }
}
