package com.enjoy.agent.knowledge.application;

/**
 * 查询改写结果。
 */
public record QueryRewriteResult(
        String originalQuery,
        String rewrittenQuery,
        boolean rewriteApplied
) {
}
