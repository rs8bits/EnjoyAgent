package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.math.BigDecimal;

/**
 * 供运行时调用的模型配置快照。
 */
public record PreparedModelConfig(
        CredentialProvider provider,
        ModelType modelType,
        String modelName,
        CredentialSource credentialSource,
        Long credentialId,
        String credentialCiphertext,
        BigDecimal temperature,
        Integer maxTokens,
        Long officialModelConfigId,
        String baseUrl,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion,
        String currency
) {

    public PreparedModelConfig(
            CredentialProvider provider,
            ModelType modelType,
            String modelName,
            CredentialSource credentialSource,
            Long credentialId,
            String credentialCiphertext,
            BigDecimal temperature,
            Integer maxTokens
    ) {
        this(
                provider,
                modelType,
                modelName,
                credentialSource,
                credentialId,
                credentialCiphertext,
                temperature,
                maxTokens,
                null,
                null,
                null,
                null,
                null
        );
    }
}
