package com.enjoy.agent.shared.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存开关和 TTL。性能对照实验可通过环境变量切换，而无需修改代码。
 */
@Component
@ConfigurationProperties(prefix = "enjoy.cache")
public class CacheProperties {

    private boolean agentConfigEnabled = true;
    private boolean agentConfigLocalEnabled = true;
    private boolean agentConfigDistributedLockEnabled = true;
    private boolean authContextEnabled = true;
    private boolean authContextLocalEnabled = true;
    private boolean sessionSummaryEnabled = true;
    private Duration defaultTtl = Duration.ofMinutes(10);
    private Duration agentConfigTtl = Duration.ofHours(1);
    private Duration agentConfigLocalTtl = Duration.ofSeconds(30);
    private Duration authContextTtl = Duration.ofSeconds(30);
    private Duration authContextLocalTtl = Duration.ofSeconds(5);
    private Duration sessionSummaryTtl = Duration.ofMinutes(30);
    private Duration distributedLockWait = Duration.ofMillis(500);
    private long agentConfigLocalMaximumSize = 10_000;
    private long authContextLocalMaximumSize = 50_000;

    public boolean isAgentConfigEnabled() {
        return agentConfigEnabled;
    }

    public void setAgentConfigEnabled(boolean agentConfigEnabled) {
        this.agentConfigEnabled = agentConfigEnabled;
    }

    public boolean isAgentConfigLocalEnabled() {
        return agentConfigLocalEnabled;
    }

    public void setAgentConfigLocalEnabled(boolean agentConfigLocalEnabled) {
        this.agentConfigLocalEnabled = agentConfigLocalEnabled;
    }

    public boolean isAgentConfigDistributedLockEnabled() {
        return agentConfigDistributedLockEnabled;
    }

    public void setAgentConfigDistributedLockEnabled(boolean agentConfigDistributedLockEnabled) {
        this.agentConfigDistributedLockEnabled = agentConfigDistributedLockEnabled;
    }

    public boolean isAuthContextEnabled() {
        return authContextEnabled;
    }

    public void setAuthContextEnabled(boolean authContextEnabled) {
        this.authContextEnabled = authContextEnabled;
    }

    public boolean isAuthContextLocalEnabled() {
        return authContextLocalEnabled;
    }

    public void setAuthContextLocalEnabled(boolean authContextLocalEnabled) {
        this.authContextLocalEnabled = authContextLocalEnabled;
    }

    public boolean isSessionSummaryEnabled() {
        return sessionSummaryEnabled;
    }

    public void setSessionSummaryEnabled(boolean sessionSummaryEnabled) {
        this.sessionSummaryEnabled = sessionSummaryEnabled;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Duration getAgentConfigTtl() {
        return agentConfigTtl;
    }

    public void setAgentConfigTtl(Duration agentConfigTtl) {
        this.agentConfigTtl = agentConfigTtl;
    }

    public Duration getAgentConfigLocalTtl() {
        return agentConfigLocalTtl;
    }

    public void setAgentConfigLocalTtl(Duration agentConfigLocalTtl) {
        this.agentConfigLocalTtl = agentConfigLocalTtl;
    }

    public Duration getAuthContextTtl() {
        return authContextTtl;
    }

    public void setAuthContextTtl(Duration authContextTtl) {
        this.authContextTtl = authContextTtl;
    }

    public Duration getAuthContextLocalTtl() {
        return authContextLocalTtl;
    }

    public void setAuthContextLocalTtl(Duration authContextLocalTtl) {
        this.authContextLocalTtl = authContextLocalTtl;
    }

    public Duration getSessionSummaryTtl() {
        return sessionSummaryTtl;
    }

    public void setSessionSummaryTtl(Duration sessionSummaryTtl) {
        this.sessionSummaryTtl = sessionSummaryTtl;
    }

    public Duration getDistributedLockWait() {
        return distributedLockWait;
    }

    public void setDistributedLockWait(Duration distributedLockWait) {
        this.distributedLockWait = distributedLockWait;
    }

    public long getAgentConfigLocalMaximumSize() {
        return agentConfigLocalMaximumSize;
    }

    public void setAgentConfigLocalMaximumSize(long agentConfigLocalMaximumSize) {
        this.agentConfigLocalMaximumSize = agentConfigLocalMaximumSize;
    }

    public long getAuthContextLocalMaximumSize() {
        return authContextLocalMaximumSize;
    }

    public void setAuthContextLocalMaximumSize(long authContextLocalMaximumSize) {
        this.authContextLocalMaximumSize = authContextLocalMaximumSize;
    }
}
