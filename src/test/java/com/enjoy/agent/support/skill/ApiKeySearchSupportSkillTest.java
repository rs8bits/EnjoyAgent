package com.enjoy.agent.support.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.credential.domain.enums.CredentialStatus;
import com.enjoy.agent.credential.domain.enums.CredentialType;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialMetadataProjection;
import com.enjoy.agent.credential.infrastructure.persistence.CredentialRepository;
import com.enjoy.agent.support.safety.SupportTextSanitizer;
import com.enjoy.agent.support.skill.builtin.ApiKeySearchSupportSkill;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

@ExtendWith(MockitoExtension.class)
class ApiKeySearchSupportSkillTest {

    @Mock
    private CredentialRepository repository;

    @Test
    void execute_shouldScopeByCurrentUserAndHideUnsafeLegacyMask() {
        CredentialMetadataProjection credential = new CredentialMetadataProjection(
                5L, "短密钥", CredentialProvider.OPENAI, CredentialType.API_KEY,
                CredentialStatus.ACTIVE, Instant.parse("2026-09-02T00:00:00Z"));
        PageRequest pageable = PageRequest.of(0, 10);
        when(repository.findSupportMetadataByUserId(11L, pageable))
                .thenReturn(new SliceImpl<>(java.util.List.of(credential), pageable, false));
        ApiKeySearchSupportSkill skill = new ApiKeySearchSupportSkill(
                repository,
                new SupportSkillPayloadWriter(
                        new ObjectMapper().findAndRegisterModules(), new SupportTextSanitizer())
        );

        SupportSkillResult result = skill.execute(
                new SupportSkillExecutionContext(7L, 11L, 19L, UUID.randomUUID(), 10),
                new SupportSkillRequest("我的 API Key", null)
        );

        verify(repository).findSupportMetadataByUserId(11L, pageable);
        assertThat(result.rawPayload())
                .contains("已配置（明文不可查看）")
                .doesNotContain("secretCiphertext")
                .doesNotContain("secretMasked");
    }
}
