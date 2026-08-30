package com.enjoy.agent.shared.config;

import com.enjoy.agent.agent.api.response.AgentResponse;
import com.enjoy.agent.shared.cache.CacheInvalidationBus;
import com.enjoy.agent.shared.cache.CacheGenerationStore;
import com.enjoy.agent.shared.cache.CacheLoadGuard;
import com.enjoy.agent.shared.cache.InvalidatingLocalCache;
import com.enjoy.agent.shared.cache.RedissonCacheGenerationStore;
import com.enjoy.agent.shared.cache.RedissonCacheInvalidationBus;
import com.enjoy.agent.shared.cache.RedissonCacheLoadGuard;
import com.enjoy.agent.shared.cache.TwoLevelCache;
import com.enjoy.agent.shared.cache.TwoLevelCacheManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final String CACHE_INFRASTRUCTURE_ENABLED =
            "${enjoy.cache.agent-config-enabled:true} || "
                    + "${enjoy.cache.auth-context-enabled:true} || "
                    + "${enjoy.cache.session-summary-enabled:true}";

    private static final String CACHE_INFRASTRUCTURE_DISABLED = "!(" + CACHE_INFRASTRUCTURE_ENABLED + ")";

    @Bean
    public RedisSerializer<Object> cacheValueSerializer(ObjectMapper objectMapper) {
        ObjectMapper cacheObjectMapper = objectMapper.copy();
        // Redis only stores immutable AgentResponse values and plain session-summary
        // strings. Do not trust type ids for the entire application package: a party
        // with Redis write access must not be able to ask Jackson to construct an
        // arbitrary domain or infrastructure class.
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(AgentResponse.class)
                .allowIfSubType(Long.class)
                .allowIfSubType(Integer.class)
                .allowIfSubType(String.class)
                .allowIfSubType(Boolean.class)
                .allowIfSubType(java.time.Instant.class)
                .build();
        cacheObjectMapper.activateDefaultTyping(
                typeValidator,
                ObjectMapper.DefaultTyping.EVERYTHING
        );
        return GenericJackson2JsonRedisSerializer.builder()
                .objectMapper(cacheObjectMapper)
                .build();
    }

    @Bean
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public RedisCacheManager redisCacheManager(
            RedisConnectionFactory connectionFactory,
            CacheProperties cacheProperties,
            RedisSerializer<Object> cacheValueSerializer
    ) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(cacheValueSerializer))
                .disableCachingNullValues()
                .entryTtl(cacheProperties.getDefaultTtl());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(CacheNames.SESSION_SUMMARY,
                        defaultConfig.entryTtl(cacheProperties.getSessionSummaryTtl()))
                .withCacheConfiguration(CacheNames.AGENT_TOOLS,
                        defaultConfig.entryTtl(cacheProperties.getDefaultTtl()))
                .withCacheConfiguration(CacheNames.AGENT_CONFIG,
                        defaultConfig.entryTtl(cacheProperties.getAgentConfigTtl()))
                .withCacheConfiguration(CacheNames.AUTH_CONTEXT,
                        defaultConfig.entryTtl(cacheProperties.getAuthContextTtl()))
                .transactionAware()
                .enableStatistics()
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        String scheme = redisProperties.getSsl().isEnabled() ? "rediss" : "redis";
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress(scheme + "://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(timeoutMillis(redisProperties.getConnectTimeout(), Duration.ofSeconds(1)))
                .setTimeout(timeoutMillis(redisProperties.getTimeout(), Duration.ofMillis(750)))
                // 缓存是 fail-open 依赖；同步命令不在请求线程内重复等待，重连由 Redisson 后台完成。
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(4);
        if (redisProperties.getUsername() != null && !redisProperties.getUsername().isBlank()) {
            server.setUsername(redisProperties.getUsername());
        }
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            server.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    private int timeoutMillis(Duration configured, Duration fallback) {
        long millis = Objects.requireNonNullElse(configured, fallback).toMillis();
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, millis));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public CacheInvalidationBus cacheInvalidationBus(RedissonClient redissonClient) {
        return new RedissonCacheInvalidationBus(redissonClient);
    }

    @Bean
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public CacheLoadGuard cacheLoadGuard(RedissonClient redissonClient, CacheProperties cacheProperties) {
        return new RedissonCacheLoadGuard(redissonClient, cacheProperties.getDistributedLockWait());
    }

    @Bean
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public CacheGenerationStore cacheGenerationStore(RedissonClient redissonClient) {
        return new RedissonCacheGenerationStore(redissonClient);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public TwoLevelCache agentConfigTwoLevelCache(
            @Qualifier("redisCacheManager") RedisCacheManager redisCacheManager,
            CacheProperties cacheProperties,
            CacheLoadGuard cacheLoadGuard,
            CacheGenerationStore cacheGenerationStore,
            CacheInvalidationBus cacheInvalidationBus,
            MeterRegistry meterRegistry
    ) {
        return new TwoLevelCache(
                CacheNames.AGENT_CONFIG,
                redisCacheManager.getCache(CacheNames.AGENT_CONFIG),
                Caffeine.newBuilder()
                        .maximumSize(cacheProperties.getAgentConfigLocalMaximumSize())
                        .expireAfterWrite(cacheProperties.getAgentConfigLocalTtl())
                        .recordStats()
                        .build(),
                cacheProperties.isAgentConfigLocalEnabled(),
                cacheProperties.isAgentConfigDistributedLockEnabled(),
                cacheLoadGuard,
                cacheGenerationStore,
                cacheInvalidationBus,
                meterRegistry
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public InvalidatingLocalCache authContextLocalCache(
            CacheProperties cacheProperties,
            CacheInvalidationBus cacheInvalidationBus
    ) {
        return new InvalidatingLocalCache(
                CacheNames.AUTH_CONTEXT,
                Caffeine.newBuilder()
                        .maximumSize(cacheProperties.getAuthContextLocalMaximumSize())
                        .expireAfterWrite(cacheProperties.getAuthContextLocalTtl())
                        .recordStats()
                        .build(),
                cacheInvalidationBus
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public TwoLevelCache sessionSummaryTwoLevelCache(
            @Qualifier("redisCacheManager") RedisCacheManager redisCacheManager,
            CacheGenerationStore cacheGenerationStore,
            CacheInvalidationBus cacheInvalidationBus,
            MeterRegistry meterRegistry
    ) {
        return new TwoLevelCache(
                CacheNames.SESSION_SUMMARY,
                redisCacheManager.getCache(CacheNames.SESSION_SUMMARY),
                Caffeine.newBuilder().maximumSize(1).build(),
                false,
                false,
                null,
                cacheGenerationStore,
                cacheInvalidationBus,
                meterRegistry
        );
    }

    @Bean
    @Primary
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_ENABLED)
    public CacheManager cacheManager(
            @Qualifier("redisCacheManager") RedisCacheManager redisCacheManager,
            @Qualifier("agentConfigTwoLevelCache") TwoLevelCache agentConfigCache,
            @Qualifier("authContextLocalCache") Cache authContextCache,
            @Qualifier("sessionSummaryTwoLevelCache") TwoLevelCache sessionSummaryCache
    ) {
        return new TwoLevelCacheManager(
                redisCacheManager,
                List.of(agentConfigCache, authContextCache, sessionSummaryCache)
        );
    }

    @Bean
    @Primary
    @ConditionalOnExpression(CACHE_INFRASTRUCTURE_DISABLED)
    public CacheManager disabledCacheManager() {
        return new NoOpCacheManager();
    }
}
