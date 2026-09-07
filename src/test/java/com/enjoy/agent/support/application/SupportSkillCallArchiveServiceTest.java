package com.enjoy.agent.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.support.domain.entity.SupportConversation;
import com.enjoy.agent.support.domain.entity.SupportSkillCall;
import com.enjoy.agent.support.domain.entity.SupportTurn;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import com.enjoy.agent.support.infrastructure.persistence.SupportSkillCallRepository;
import com.enjoy.agent.support.infrastructure.persistence.SupportTurnRepository;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import com.enjoy.agent.support.skill.SupportSkillRisk;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证 Skill 审计入口同样服从 Turn 所有权栅栏。 */
@ExtendWith(MockitoExtension.class)
class SupportSkillCallArchiveServiceTest {

    @Mock
    private SupportSkillCallRepository skillCallRepository;
    @Mock
    private SupportTurnRepository turnRepository;

    @Test
    void staleOwner_shouldNotStartAnotherSkillCall() {
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        AppUser appUser = new AppUser();
        appUser.setId(11L);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(19L);
        conversation.setTenant(tenant);
        conversation.setUser(appUser);

        UUID turnId = UUID.randomUUID();
        SupportTurn turn = new SupportTurn();
        turn.setId(turnId);
        turn.setConversation(conversation);
        turn.setOwnerToken(UUID.randomUUID());
        turn.setStatus(SupportTurnStatus.RUNNING);
        when(turnRepository.findByIdAndConversation_Tenant_IdAndConversation_User_Id(
                turnId, 7L, 11L)).thenReturn(Optional.of(turn));

        AuthenticatedUser user = new AuthenticatedUser(
                11L, "user@example.com", "用户", 7L, "tenant-7", "租户 7",
                TenantMemberRole.MEMBER, SystemRole.USER);
        SupportSkillManifest manifest = new SupportSkillManifest(
                "agent_search", "1.0.0", "查询 Agent", "测试 Skill", "{}", "{}",
                SupportSkillRisk.READ, "agent:read", false, 1_000);
        SupportSkillCallArchiveService service = new SupportSkillCallArchiveService(
                skillCallRepository, turnRepository);

        assertThatThrownBy(() -> service.start(
                user, turnId, UUID.randomUUID(), manifest, "{}"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("SUPPORT_TURN_OWNERSHIP_LOST"));

        verify(skillCallRepository, never()).saveAndFlush(any(SupportSkillCall.class));
    }
}
