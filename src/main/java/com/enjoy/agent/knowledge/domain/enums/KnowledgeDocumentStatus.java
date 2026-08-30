package com.enjoy.agent.knowledge.domain.enums;

/**
 * 知识库文档处理状态。
 */
public enum KnowledgeDocumentStatus {
    QUEUED,
    PROCESSING,
    RETRYING,
    INDEXING,
    READY,
    FAILED,
    DELETING
}
