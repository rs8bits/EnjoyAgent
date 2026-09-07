package com.enjoy.agent.support.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayConversationMessage;
import java.util.List;

/** 完成严格预算裁剪后的模型输入。 */
public record SupportPromptContext(
        String systemPrompt,
        String sessionMemorySummary,
        String retrievalContext,
        List<ModelGatewayConversationMessage> messages,
        int estimatedInputTokenUpperBound
) {
}
