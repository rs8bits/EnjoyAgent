package com.enjoy.agent.model.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.agent.infrastructure.persistence.AgentRepository;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.billing.application.BillingProperties;
import com.enjoy.agent.billing.infrastructure.persistence.BillingCreditReservationRepository;
import com.enjoy.agent.billing.infrastructure.persistence.BillingUsageEventRepository;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.api.request.CreateOfficialModelConfigRequest;
import com.enjoy.agent.model.api.request.UpdateOfficialModelConfigRequest;
import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.entity.OfficialModelCredential;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelCredentialRepository;
import com.enjoy.agent.shared.crypto.AesCryptoService;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.shared.security.OutboundUrlPolicy;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OfficialModelAdminApplicationServiceTest {

    private OfficialModelCredentialRepository credentialRepository;
    private OfficialModelConfigRepository configRepository;
    private AgentRepository agentRepository;
    private BillingCreditReservationRepository reservationRepository;
    private BillingUsageEventRepository usageEventRepository;
    private OfficialModelAdminApplicationService service;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(OfficialModelCredentialRepository.class);
        configRepository = mock(OfficialModelConfigRepository.class);
        agentRepository = mock(AgentRepository.class);
        reservationRepository = mock(BillingCreditReservationRepository.class);
        usageEventRepository = mock(BillingUsageEventRepository.class);
        BillingProperties billingProperties = new BillingProperties();
        billingProperties.setCurrency("CNY");
        service = new OfficialModelAdminApplicationService(
                credentialRepository,
                configRepository,
                agentRepository,
                reservationRepository,
                usageEventRepository,
                billingProperties,
                mock(AesCryptoService.class),
                mock(OutboundUrlPolicy.class)
        );

        AuthenticatedUser admin = new AuthenticatedUser(
                1L,
                "admin@example.com",
                "Admin",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.ADMIN
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.authorities())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createConfig_shouldRejectCurrencyOutsidePlatformBillingCurrencyBeforeRepositoryAccess() {
        assertCurrencyMismatch(() -> service.createConfig(createRequest("USD")));

        verify(configRepository, never()).existsByName(any());
        verify(credentialRepository, never()).findById(any());
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateConfig_shouldRejectCurrencyOutsidePlatformBillingCurrencyBeforeRepositoryAccess() {
        assertCurrencyMismatch(() -> service.updateConfig(51L, updateRequest("USD")));

        verify(configRepository, never()).findById(any());
        verify(configRepository, never()).saveAndFlush(any());
    }

    @Test
    void createConfig_shouldNormalizeMatchingCurrency() {
        OfficialModelCredential credential = credential(11L);
        when(credentialRepository.findById(11L)).thenReturn(Optional.of(credential));
        when(configRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            OfficialModelConfig config = invocation.getArgument(0);
            config.setId(51L);
            return config;
        });

        var response = service.createConfig(createRequest(" cny "));

        assertThat(response.currency()).isEqualTo("CNY");
    }

    @Test
    void deleteCredential_shouldReturnConflictWhenConfigReferencesIt() {
        OfficialModelCredential credential = credential(11L);
        when(credentialRepository.findById(11L)).thenReturn(Optional.of(credential));
        when(configRepository.existsByOfficialCredential_Id(11L)).thenReturn(true);

        assertInUse(() -> service.deleteCredential(11L), "OFFICIAL_MODEL_CREDENTIAL_IN_USE");

        verify(credentialRepository, never()).delete(any());
        verify(credentialRepository, never()).flush();
    }

    @Test
    void deleteCredential_shouldTranslateConcurrentForeignKeyViolationToConflict() {
        OfficialModelCredential credential = credential(11L);
        when(credentialRepository.findById(11L)).thenReturn(Optional.of(credential));
        doThrow(new DataIntegrityViolationException("fk race"))
                .when(credentialRepository).flush();

        assertInUse(() -> service.deleteCredential(11L), "OFFICIAL_MODEL_CREDENTIAL_IN_USE");
    }

    @Test
    void deleteConfig_shouldReturnConflictWhenAgentReferencesIt() {
        OfficialModelConfig config = config(51L);
        when(configRepository.findById(51L)).thenReturn(Optional.of(config));
        when(agentRepository.existsByOfficialModelConfig_Id(51L)).thenReturn(true);

        assertInUse(() -> service.deleteConfig(51L), "OFFICIAL_MODEL_CONFIG_IN_USE");

        verify(configRepository, never()).delete(any());
        verify(configRepository, never()).flush();
    }

    @Test
    void deleteConfig_shouldReturnConflictWhenReservationReferencesIt() {
        OfficialModelConfig config = config(51L);
        when(configRepository.findById(51L)).thenReturn(Optional.of(config));
        when(reservationRepository.existsByOfficialModelConfigId(51L)).thenReturn(true);

        assertInUse(() -> service.deleteConfig(51L), "OFFICIAL_MODEL_CONFIG_IN_USE");

        verify(configRepository, never()).delete(any());
        verify(configRepository, never()).flush();
    }

    @Test
    void deleteConfig_shouldReturnConflictWhenUsageEventReferencesIt() {
        OfficialModelConfig config = config(51L);
        when(configRepository.findById(51L)).thenReturn(Optional.of(config));
        when(usageEventRepository.existsByOfficialModelConfigId(51L)).thenReturn(true);

        assertInUse(() -> service.deleteConfig(51L), "OFFICIAL_MODEL_CONFIG_IN_USE");

        verify(configRepository, never()).delete(any());
        verify(configRepository, never()).flush();
    }

    @Test
    void deleteConfig_shouldTranslateConcurrentForeignKeyViolationToConflict() {
        OfficialModelConfig config = config(51L);
        when(configRepository.findById(51L)).thenReturn(Optional.of(config));
        doThrow(new DataIntegrityViolationException("fk race"))
                .when(configRepository).flush();

        assertInUse(() -> service.deleteConfig(51L), "OFFICIAL_MODEL_CONFIG_IN_USE");
    }

    private void assertCurrencyMismatch(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("OFFICIAL_MODEL_CURRENCY_MISMATCH");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    private void assertInUse(ThrowingOperation operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(code);
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    private CreateOfficialModelConfigRequest createRequest(String currency) {
        return new CreateOfficialModelConfigRequest(
                "Official Qwen",
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                11L,
                new BigDecimal("0.2"),
                2048,
                new BigDecimal("0.8"),
                new BigDecimal("2.0"),
                currency,
                null,
                true
        );
    }

    private UpdateOfficialModelConfigRequest updateRequest(String currency) {
        return new UpdateOfficialModelConfigRequest(
                "Official Qwen",
                CredentialProvider.OPENAI,
                ModelType.CHAT,
                "qwen-plus",
                11L,
                new BigDecimal("0.2"),
                2048,
                new BigDecimal("0.8"),
                new BigDecimal("2.0"),
                currency,
                null,
                true
        );
    }

    private OfficialModelCredential credential(Long id) {
        OfficialModelCredential credential = new OfficialModelCredential();
        credential.setId(id);
        credential.setName("DashScope");
        credential.setProvider(CredentialProvider.OPENAI);
        credential.setEnabled(true);
        return credential;
    }

    private OfficialModelConfig config(Long id) {
        OfficialModelConfig config = new OfficialModelConfig();
        config.setId(id);
        return config;
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
