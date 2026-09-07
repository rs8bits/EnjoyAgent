package com.enjoy.agent.knowledge.infrastructure.persistence;

import java.time.Instant;

/** 不包含 embedding 模型及其凭证的知识库最小只读投影。 */
public record KnowledgeBaseSummaryProjection(
        Long id,
        String name,
        String description,
        boolean enabled,
        Instant updatedAt
) {
}
