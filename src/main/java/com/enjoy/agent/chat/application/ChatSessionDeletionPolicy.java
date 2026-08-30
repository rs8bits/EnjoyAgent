package com.enjoy.agent.chat.application;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.chat.domain.entity.ChatSession;
import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Authorization boundary for the destructive chat-session delete operation. */
@Component
public class ChatSessionDeletionPolicy {

    public void requireAllowed(AuthenticatedUser currentUser, ChatSession session) {
        boolean creator = session.getCreatedBy() != null
                && Objects.equals(session.getCreatedBy().getId(), currentUser.userId());
        boolean tenantOwner = currentUser.role() == TenantMemberRole.OWNER;
        boolean systemAdmin = currentUser.systemRole() == SystemRole.ADMIN;
        if (!creator && !tenantOwner && !systemAdmin) {
            throw new ApiException(
                    "CHAT_SESSION_DELETE_FORBIDDEN",
                    "Only the session creator or an owner may delete this chat session",
                    HttpStatus.FORBIDDEN
            );
        }
    }
}
