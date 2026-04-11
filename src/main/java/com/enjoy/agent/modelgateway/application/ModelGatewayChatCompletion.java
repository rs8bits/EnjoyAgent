package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;

/**
 * 一次模型聊天补全过程结果，可能包含工具调用请求。
 */
public record ModelGatewayChatCompletion(
        String content,
        List<ModelGatewayToolCall> toolCalls,
        CredentialProvider provider,
        String modelName,
        CredentialSource credentialSource,
        Long credentialId,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {

    public ModelGatewayResult toResult() {
        return new ModelGatewayResult(
                content,
                provider,
                modelName,
                credentialSource,
                credentialId,
                latencyMs,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }
}
