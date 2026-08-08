package com.enjoy.agent.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.enjoy.agent.shared.cache.CacheGenerationStore;
import com.enjoy.agent.shared.cache.CacheInvalidationBus;
import com.enjoy.agent.shared.cache.CacheLoadGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CacheInfrastructureDisabledTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class, ObjectMapperConfig.class)
            .withPropertyValues(
                    "enjoy.cache.agent-config-enabled=false",
                    "enjoy.cache.auth-context-enabled=false",
                    "enjoy.cache.session-summary-enabled=false",
                    "spring.data.redis.host=127.0.0.1",
                    "spring.data.redis.port=6398"
            );

    @Test
    void allCacheSwitchesOff_shouldCreateNoOpManagerWithoutRedisInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(NoOpCacheManager.class);
            assertThat(context).doesNotHaveBean(RedissonClient.class);
            assertThat(context).doesNotHaveBean(CacheInvalidationBus.class);
            assertThat(context).doesNotHaveBean(CacheLoadGuard.class);
            assertThat(context).doesNotHaveBean(CacheGenerationStore.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectMapperConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
