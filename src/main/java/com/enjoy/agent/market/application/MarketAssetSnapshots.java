package com.enjoy.agent.market.application;

import com.enjoy.agent.agent.domain.enums.AgentChatModelBindingType;
import com.enjoy.agent.agent.domain.enums.ContextStrategy;
import com.enjoy.agent.agent.domain.enums.MemoryStrategy;
import com.enjoy.agent.mcp.domain.enums.McpAuthType;
import com.enjoy.agent.mcp.domain.enums.McpToolRiskLevel;
import com.enjoy.agent.mcp.domain.enums.McpTransportType;
import java.util.List;

record AgentMarketSnapshot(
        String name,
        String description,
        String systemPrompt,
        AgentChatModelBindingType chatModelBindingType,
        Long officialModelConfigId,
        boolean rerankEnabled,
        ContextStrategy contextStrategy,
        Integer contextWindowSize,
        boolean memoryEnabled,
        MemoryStrategy memoryStrategy,
        Integer memoryUpdateMessageThreshold,
        boolean enabled,
        KnowledgeBaseMarketSnapshot knowledgeBaseSnapshot,
        List<McpServerMarketSnapshot> mcpServerSnapshots,
        String knowledgeBaseName,
        List<String> toolNames
) {
}

record KnowledgeBaseMarketSnapshot(
        String name,
        String description,
        boolean enabled,
        List<KnowledgeDocumentMarketSnapshot> documents
) {
}

record KnowledgeDocumentMarketSnapshot(
        String fileName,
        String contentType,
        Long fileSize,
        String storageBucket,
        String storageObjectKey
) {
}

record McpServerMarketSnapshot(
        String name,
        String description,
        String baseUrl,
        McpTransportType transportType,
        McpAuthType authType,
        boolean enabled,
        List<McpToolMarketSnapshot> tools
) {
}

record McpToolMarketSnapshot(
        String name,
        String description,
        String inputSchemaJson,
        McpToolRiskLevel riskLevel,
        boolean enabled
) {
}
