package com.enjoy.agent.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import org.junit.jupiter.api.Test;

class CacheKeysTest {

    @Test
    void agent_shouldIncludeTenantBoundary() {
        assertThat(CacheKeys.agent(7L, 42L)).isEqualTo("7:42");
        assertThat(CacheKeys.agent(8L, 42L)).isEqualTo("8:42");
    }

    @Test
    void authenticationContext_shouldIncludeTenantAndUser() {
        assertThat(CacheKeys.authenticationContext(7L, 101L)).isEqualTo("7:101");
        assertThat(CacheKeys.authenticationContext(8L, 101L)).isEqualTo("8:101");
    }

    @Test
    void sessionSummary_shouldIncludeTenantSessionAndStrategy() {
        assertThat(CacheKeys.sessionSummary(7L, 8L, MemoryStrategy.SESSION_SUMMARY))
                .isEqualTo("7:8:SESSION_SUMMARY");
    }
}
