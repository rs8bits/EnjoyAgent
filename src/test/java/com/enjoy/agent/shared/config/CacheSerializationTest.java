package com.enjoy.agent.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.agent.api.response.AgentResponse;
import com.enjoy.agent.auth.domain.enums.SystemRole;
import com.enjoy.agent.shared.security.AuthenticatedUser;
import com.enjoy.agent.tenant.domain.enums.TenantMemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

class CacheSerializationTest {

    @Test
    void cacheValueSerializer_shouldRoundTripAgentResponseWithInstantFields() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisSerializer<Object> serializer = new CacheConfig().cacheValueSerializer(objectMapper);
        Instant createdAt = Instant.parse("2026-08-03T10:15:30Z");
        Instant updatedAt = Instant.parse("2026-08-03T10:16:30Z");
        AgentResponse original = new AgentResponse(
                42L,
                7L,
                "tenant-scoped-agent",
                "description",
                "system prompt",
                "USER_MODEL",
                11L,
                "chat-model",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "SLIDING_WINDOW",
                10,
                false,
                null,
                6,
                true,
                createdAt,
                updatedAt
        );

        byte[] bytes = serializer.serialize(original);
        Object restored = serializer.deserialize(bytes);

        assertThat(restored).isInstanceOf(AgentResponse.class).isEqualTo(original);
    }

    @Test
    void cacheValueSerializer_shouldRoundTripAuthenticationSnapshot() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisSerializer<Object> serializer = new CacheConfig().cacheValueSerializer(objectMapper);
        AuthenticatedUser original = new AuthenticatedUser(
                101L,
                "user@example.test",
                "User",
                7L,
                "tenant-7",
                "Tenant 7",
                TenantMemberRole.OWNER,
                SystemRole.USER
        );

        byte[] bytes = serializer.serialize(original);
        Object restored = serializer.deserialize(bytes);

        assertThat(restored).isInstanceOf(AuthenticatedUser.class).isEqualTo(original);
    }
}
