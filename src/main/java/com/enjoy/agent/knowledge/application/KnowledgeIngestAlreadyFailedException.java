package com.enjoy.agent.knowledge.application;

/**
 * 已经最终失败的消息仍应被拒绝到 parking queue，而不是作为业务成功 ACK。
 */
public class KnowledgeIngestAlreadyFailedException extends RuntimeException {

    public KnowledgeIngestAlreadyFailedException(Long documentId) {
        super("Knowledge document is already FAILED: documentId=" + documentId);
    }
}
