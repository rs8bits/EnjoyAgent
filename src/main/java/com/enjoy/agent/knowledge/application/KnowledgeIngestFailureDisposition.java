package com.enjoy.agent.knowledge.application;

/**
 * Worker 失败后的消息处置结果。
 */
public enum KnowledgeIngestFailureDisposition {
    RETRY_SCHEDULED,
    PARK,
    IGNORE
}
