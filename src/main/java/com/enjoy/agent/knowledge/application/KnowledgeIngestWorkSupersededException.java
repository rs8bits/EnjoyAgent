package com.enjoy.agent.knowledge.application;

/**
 * 当前 Worker 的持久化处理令牌已经被更新的 RabbitMQ delivery 取代。
 */
public class KnowledgeIngestWorkSupersededException extends RuntimeException {

    public KnowledgeIngestWorkSupersededException(Long documentId, int deliveryAttempt) {
        super("Knowledge ingest work was superseded: documentId=" + documentId + ", attempt=" + deliveryAttempt);
    }
}
