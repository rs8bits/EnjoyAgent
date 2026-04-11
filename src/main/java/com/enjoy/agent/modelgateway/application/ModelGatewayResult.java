package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;

/**
 * 模型调用成功后的返回结果。
 */
public record ModelGatewayResult(
        String content,
        CredentialProvider provider,
        String modelName,
        CredentialSource credentialSource,
        Long credentialId,
        Long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
