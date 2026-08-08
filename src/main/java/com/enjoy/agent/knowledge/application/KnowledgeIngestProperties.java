package com.enjoy.agent.knowledge.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步文档导入、RabbitMQ Worker 与 Outbox Relay 配置。
 */
@ConfigurationProperties(prefix = "enjoy.knowledge.ingest")
public class KnowledgeIngestProperties {

    private boolean asyncEnabled = true;
    private String exchange = "enjoy.knowledge.events";
    private String routingKey = "knowledge.document.ingest";
    private String queue = "knowledge.document.ingest.queue";
    private String parkingRoutingKey = "knowledge.document.ingest.parking";
    private String parkingQueue = "knowledge.document.ingest.parking";
    private int workerConcurrency = 4;
    private int prefetch = 2;
    private int maxAttempts = 3;
    private List<Duration> retryDelays = new ArrayList<>(List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30)
    ));
    private int outboxBatchSize = 50;
    private Duration outboxClaimTimeout = Duration.ofSeconds(30);
    private Duration publisherConfirmTimeout = Duration.ofSeconds(5);
    private Duration outboxPublishRetryDelay = Duration.ofSeconds(2);
    private Duration processingLeaseDuration = Duration.ofMinutes(10);

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    public void setAsyncEnabled(boolean asyncEnabled) {
        this.asyncEnabled = asyncEnabled;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getParkingRoutingKey() {
        return parkingRoutingKey;
    }

    public void setParkingRoutingKey(String parkingRoutingKey) {
        this.parkingRoutingKey = parkingRoutingKey;
    }

    public String getParkingQueue() {
        return parkingQueue;
    }

    public void setParkingQueue(String parkingQueue) {
        this.parkingQueue = parkingQueue;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public List<Duration> getRetryDelays() {
        return retryDelays;
    }

    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = retryDelays;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public Duration getOutboxClaimTimeout() {
        return outboxClaimTimeout;
    }

    public void setOutboxClaimTimeout(Duration outboxClaimTimeout) {
        this.outboxClaimTimeout = outboxClaimTimeout;
    }

    public Duration getPublisherConfirmTimeout() {
        return publisherConfirmTimeout;
    }

    public void setPublisherConfirmTimeout(Duration publisherConfirmTimeout) {
        this.publisherConfirmTimeout = publisherConfirmTimeout;
    }

    public Duration getOutboxPublishRetryDelay() {
        return outboxPublishRetryDelay;
    }

    public void setOutboxPublishRetryDelay(Duration outboxPublishRetryDelay) {
        this.outboxPublishRetryDelay = outboxPublishRetryDelay;
    }

    public Duration getProcessingLeaseDuration() {
        return processingLeaseDuration;
    }

    public void setProcessingLeaseDuration(Duration processingLeaseDuration) {
        this.processingLeaseDuration = processingLeaseDuration;
    }

    public Duration retryDelayForAttempt(int completedAttempt) {
        if (retryDelays == null || retryDelays.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.max(0, Math.min(attemptInCycle(completedAttempt) - 1, retryDelays.size() - 1));
        return retryDelays.get(index);
    }

    public int attemptInCycle(int deliveryAttempt) {
        int cycleSize = Math.max(1, maxAttempts);
        return Math.floorMod(Math.max(1, deliveryAttempt) - 1, cycleSize) + 1;
    }

    public boolean isLastAttemptInCycle(int deliveryAttempt) {
        return attemptInCycle(deliveryAttempt) >= Math.max(1, maxAttempts);
    }
}
