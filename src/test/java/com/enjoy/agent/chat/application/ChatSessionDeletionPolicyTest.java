package com.enjoy.agent.chat.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import org.junit.jupiter.api.Test;

class ChatSessionDeletionPolicyTest {

    private final ChatSessionDeletionPolicy policy = new ChatSessionDeletionPolicy();

    @Test
    void ordinaryMemberCannotDeleteAnotherMembersSession() {
        ChatSession session = sessionCreatedBy(99L);
        AuthenticatedUser member = user(7L, TenantMemberRole.MEMBER, SystemRole.USER);

        assertThatThrownBy(() -> policy.requireAllowed(member, session))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getCode())
                                .isEqualTo("CHAT_SESSION_DELETE_FORBIDDEN"));
    }

    @Test
    void creatorOwnerAndSystemAdminCanDelete() {
        ChatSession creatorSession = sessionCreatedBy(7L);
        assertDoesNotThrow(() -> policy.requireAllowed(
                user(7L, TenantMemberRole.MEMBER, SystemRole.USER), creatorSession));

        ChatSession finalSession = sessionCreatedBy(99L);
        assertDoesNotThrow(() -> policy.requireAllowed(
                user(7L, TenantMemberRole.OWNER, SystemRole.USER), finalSession));
        assertDoesNotThrow(() -> policy.requireAllowed(
                user(7L, TenantMemberRole.MEMBER, SystemRole.ADMIN), finalSession));
    }

    private ChatSession sessionCreatedBy(Long userId) {
        AppUser creator = new AppUser();
        creator.setId(userId);
        ChatSession session = new ChatSession();
        session.setCreatedBy(creator);
        return session;
    }

    private AuthenticatedUser user(Long userId, TenantMemberRole role, SystemRole systemRole) {
        return new AuthenticatedUser(
                userId, "member@example.com", "Member", 3L, "tenant", "Tenant", role, systemRole);
    }
}
