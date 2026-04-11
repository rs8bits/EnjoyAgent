package com.enjoy.agent.knowledge.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后初始化知识检索索引。
 */
@Component
public class KnowledgeSearchIndexInitializer {

    private final KnowledgeSearchGateway knowledgeSearchGateway;

    public KnowledgeSearchIndexInitializer(KnowledgeSearchGateway knowledgeSearchGateway) {
        this.knowledgeSearchGateway = knowledgeSearchGateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndex() {
        knowledgeSearchGateway.ensureIndexExists();
    }
}
