package com.enjoy.agent.billing.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户钱包、充值单和异步计费配置。
 */
@ConfigurationProperties(prefix = "enjoy.billing")
public class BillingProperties {

    private boolean enabled = false;
    private String currency = "CNY";
    private Rabbit rabbit = new Rabbit();
    private int reservationTokens = 4096;
    private int reservationMaxInputTokens = 1_000_000;
    private int reservationMaxOutputTokens = 131_072;
    private Duration reservationTtl = Duration.ofMinutes(10);
    private Duration reservationReleaseClaimTimeout = Duration.ofMinutes(1);
    private BigDecimal minimumReservationAmount = new BigDecimal("0.000001");
    private int processingMaxAttempts = 5;
    private Duration processingRetryDelay = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Rabbit getRabbit() {
        return rabbit;
    }

    public void setRabbit(Rabbit rabbit) {
        this.rabbit = rabbit;
    }

    public int getReservationTokens() {
        return reservationTokens;
    }

    public void setReservationTokens(int reservationTokens) {
        if (reservationTokens < 1) {
            throw new IllegalArgumentException("Billing reservation tokens must be positive");
        }
        this.reservationTokens = reservationTokens;
    }

    public int getReservationMaxInputTokens() {
        return reservationMaxInputTokens;
    }

    public void setReservationMaxInputTokens(int reservationMaxInputTokens) {
        if (reservationMaxInputTokens < 1) {
            throw new IllegalArgumentException("Billing maximum reservation input tokens must be positive");
        }
        this.reservationMaxInputTokens = reservationMaxInputTokens;
    }

    public int getReservationMaxOutputTokens() {
        return reservationMaxOutputTokens;
    }

    public void setReservationMaxOutputTokens(int reservationMaxOutputTokens) {
        if (reservationMaxOutputTokens < 1) {
            throw new IllegalArgumentException("Billing maximum reservation output tokens must be positive");
        }
        this.reservationMaxOutputTokens = reservationMaxOutputTokens;
    }

    public Duration getReservationTtl() {
        return reservationTtl;
    }

    public void setReservationTtl(Duration reservationTtl) {
        if (reservationTtl == null || reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("Billing reservation TTL must be positive");
        }
        this.reservationTtl = reservationTtl;
    }

    public Duration getReservationReleaseClaimTimeout() {
        return reservationReleaseClaimTimeout;
    }

    public void setReservationReleaseClaimTimeout(Duration reservationReleaseClaimTimeout) {
        if (reservationReleaseClaimTimeout == null
                || reservationReleaseClaimTimeout.isNegative()
                || reservationReleaseClaimTimeout.isZero()) {
            throw new IllegalArgumentException("Billing reservation release claim timeout must be positive");
        }
        this.reservationReleaseClaimTimeout = reservationReleaseClaimTimeout;
    }

    public BigDecimal getMinimumReservationAmount() {
        return minimumReservationAmount;
    }

    public void setMinimumReservationAmount(BigDecimal minimumReservationAmount) {
        if (minimumReservationAmount == null || minimumReservationAmount.signum() < 0) {
            throw new IllegalArgumentException("Minimum reservation amount must not be negative");
        }
        this.minimumReservationAmount = minimumReservationAmount;
    }

    public int getProcessingMaxAttempts() {
        return processingMaxAttempts;
    }

    public void setProcessingMaxAttempts(int processingMaxAttempts) {
        if (processingMaxAttempts < 1) {
            throw new IllegalArgumentException("Billing processing max attempts must be positive");
        }
        this.processingMaxAttempts = processingMaxAttempts;
    }

    public Duration getProcessingRetryDelay() {
        return processingRetryDelay;
    }

    public void setProcessingRetryDelay(Duration processingRetryDelay) {
        if (processingRetryDelay == null || processingRetryDelay.isNegative()) {
            throw new IllegalArgumentException("Billing processing retry delay must not be negative");
        }
        this.processingRetryDelay = processingRetryDelay;
    }

    public static class Rabbit {

        private String exchange = "enjoy.agent.events";
        private String usageRoutingKey = "billing.usage";
        private String usageQueue = "billing.usage.queue";
        private String usageDeadLetterRoutingKey = "billing.usage.dlq";
        private String usageDeadLetterQueue = "billing.usage.dlq";
        private Duration publisherConfirmTimeout = Duration.ofSeconds(5);
        private Duration outboxClaimTimeout = Duration.ofSeconds(30);
        private Duration outboxPublishRetryDelay = Duration.ofSeconds(2);
        private Duration reconciliationDelay = Duration.ofMinutes(1);
        private int outboxBatchSize = 50;

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getUsageRoutingKey() {
            return usageRoutingKey;
        }

        public void setUsageRoutingKey(String usageRoutingKey) {
            this.usageRoutingKey = usageRoutingKey;
        }

        public String getUsageQueue() {
            return usageQueue;
        }

        public void setUsageQueue(String usageQueue) {
            this.usageQueue = usageQueue;
        }

        public String getUsageDeadLetterRoutingKey() {
            return usageDeadLetterRoutingKey;
        }

        public void setUsageDeadLetterRoutingKey(String usageDeadLetterRoutingKey) {
            this.usageDeadLetterRoutingKey = usageDeadLetterRoutingKey;
        }

        public String getUsageDeadLetterQueue() {
            return usageDeadLetterQueue;
        }

        public void setUsageDeadLetterQueue(String usageDeadLetterQueue) {
            this.usageDeadLetterQueue = usageDeadLetterQueue;
        }

        public Duration getPublisherConfirmTimeout() {
            return publisherConfirmTimeout;
        }

        public void setPublisherConfirmTimeout(Duration publisherConfirmTimeout) {
            this.publisherConfirmTimeout = requirePositive(publisherConfirmTimeout, "publisher confirm timeout");
        }

        public Duration getOutboxClaimTimeout() {
            return outboxClaimTimeout;
        }

        public void setOutboxClaimTimeout(Duration outboxClaimTimeout) {
            this.outboxClaimTimeout = requirePositive(outboxClaimTimeout, "outbox claim timeout");
        }

        public Duration getOutboxPublishRetryDelay() {
            return outboxPublishRetryDelay;
        }

        public void setOutboxPublishRetryDelay(Duration outboxPublishRetryDelay) {
            this.outboxPublishRetryDelay = requirePositive(outboxPublishRetryDelay, "outbox publish retry delay");
        }

        public Duration getReconciliationDelay() {
            return reconciliationDelay;
        }

        public void setReconciliationDelay(Duration reconciliationDelay) {
            this.reconciliationDelay = requirePositive(reconciliationDelay, "reconciliation delay");
        }

        public int getOutboxBatchSize() {
            return outboxBatchSize;
        }

        public void setOutboxBatchSize(int outboxBatchSize) {
            if (outboxBatchSize < 1) {
                throw new IllegalArgumentException("Billing outbox batch size must be positive");
            }
            this.outboxBatchSize = outboxBatchSize;
        }

        private Duration requirePositive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("Billing " + name + " must be positive");
            }
            return value;
        }
    }
}
