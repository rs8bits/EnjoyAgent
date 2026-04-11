package com.enjoy.agent.shared.security;

import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import java.util.List;
import java.util.ArrayList;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 当前登录用户在系统中的安全上下文表示。
 */
public record AuthenticatedUser(
        Long userId,
        String email,
        String displayName,
        Long tenantId,
        String tenantCode,
        String tenantName,
        TenantMemberRole role,
        SystemRole systemRole
) {

    /**
     * 把当前用户角色转换成 Spring Security 可识别的权限集合。
     */
    public List<? extends GrantedAuthority> authorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + systemRole.name()));
        return authorities;
    }
}
