package com.enjoy.agent.knowledge.domain.enums;

/**
 * 知识文档导入消息的可靠投递状态。
 */
public enum KnowledgeIngestOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED
}
