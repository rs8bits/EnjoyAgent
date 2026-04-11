package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 模型网关调用失败异常。
 */
public class ModelGatewayInvocationException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final CredentialProvider provider;
    private final String modelName;
    private final CredentialSource credentialSource;
    private final Long credentialId;
    private final Long latencyMs;

    public ModelGatewayInvocationException(
            String code,
            String message,
            HttpStatus status,
            CredentialProvider provider,
            String modelName,
            CredentialSource credentialSource,
            Long credentialId,
            Long latencyMs,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.status = status;
        this.provider = provider;
        this.modelName = modelName;
        this.credentialSource = credentialSource;
        this.credentialId = credentialId;
        this.latencyMs = latencyMs;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public CredentialProvider getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public CredentialSource getCredentialSource() {
        return credentialSource;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    /**
     * 转换成统一的业务异常，交给全局异常处理器输出。
     */
    public ApiException toApiException() {
        return new ApiException(code, getMessage(), status);
    }
}
