package com.enjoy.agent.billing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户钱包、充值单和异步计费配置。
 */
@ConfigurationProperties(prefix = "enjoy.billing")
public class BillingProperties {

    private boolean enabled = false;
    private String currency = "CNY";
    private Rabbit rabbit = new Rabbit();

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

    public static class Rabbit {

        private String exchange = "enjoy.agent.events";
        private String usageRoutingKey = "billing.usage";
        private String usageQueue = "billing.usage.queue";
        private String usageDeadLetterRoutingKey = "billing.usage.dlq";
        private String usageDeadLetterQueue = "billing.usage.dlq";

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
    }
}
