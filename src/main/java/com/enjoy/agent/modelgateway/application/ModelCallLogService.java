package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.modelgateway.domain.entity.ModelCallLog;
import com.enjoy.agent.modelgateway.domain.enums.ModelCallStatus;
import com.enjoy.agent.modelgateway.infrastructure.persistence.ModelCallLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型调用日志应用服务。
 */
@Service
public class ModelCallLogService {

    private final ModelCallLogRepository modelCallLogRepository;

    public ModelCallLogService(ModelCallLogRepository modelCallLogRepository) {
        this.modelCallLogRepository = modelCallLogRepository;
    }

    /**
     * 记录一次成功的模型调用。
     */
    @Transactional
    public ModelCallLog recordSuccess(PreparedChatTurn preparedChatTurn, ModelGatewayResult result) {
        ModelCallLog log = createBaseLog(preparedChatTurn);
        log.setProvider(result.provider());
        log.setModelName(result.modelName());
        log.setCredentialSource(result.credentialSource());
        log.setCredentialId(normalizeCredentialId(result.credentialSource(), result.credentialId()));
        log.setStatus(ModelCallStatus.SUCCESS);
        log.setLatencyMs(result.latencyMs());
        log.setPromptTokens(result.promptTokens());
        log.setCompletionTokens(result.completionTokens());
        log.setTotalTokens(result.totalTokens());
        return modelCallLogRepository.saveAndFlush(log);
    }

    /**
     * 记录一次成功的模型调用，即使这次返回的是工具调用请求也计作成功。
     */
    @Transactional
    public ModelCallLog recordSuccess(PreparedChatTurn preparedChatTurn, ModelGatewayChatCompletion completion) {
        ModelCallLog log = createBaseLog(preparedChatTurn);
        log.setProvider(completion.provider());
        log.setModelName(completion.modelName());
        log.setCredentialSource(completion.credentialSource());
        log.setCredentialId(normalizeCredentialId(completion.credentialSource(), completion.credentialId()));
        log.setStatus(ModelCallStatus.SUCCESS);
        log.setLatencyMs(completion.latencyMs());
        log.setPromptTokens(completion.promptTokens());
        log.setCompletionTokens(completion.completionTokens());
        log.setTotalTokens(completion.totalTokens());
        return modelCallLogRepository.saveAndFlush(log);
    }

    /**
     * 记录一次失败的模型调用。
     */
    @Transactional
    public void recordFailure(PreparedChatTurn preparedChatTurn, ModelGatewayInvocationException ex) {
        ModelCallLog log = createBaseLog(preparedChatTurn);
        log.setProvider(ex.getProvider());
        log.setModelName(ex.getModelName());
        log.setCredentialSource(ex.getCredentialSource());
        log.setCredentialId(normalizeCredentialId(ex.getCredentialSource(), ex.getCredentialId()));
        log.setStatus(ModelCallStatus.FAILED);
        log.setLatencyMs(ex.getLatencyMs());
        log.setErrorCode(ex.getCode());
        log.setErrorMessage(truncate(ex.getMessage(), 1000));
        modelCallLogRepository.save(log);
    }

    /**
     * 构造日志公共字段。
     */
    private ModelCallLog createBaseLog(PreparedChatTurn preparedChatTurn) {
        ModelCallLog log = new ModelCallLog();
        log.setTenantId(preparedChatTurn.tenantId());
        log.setUserId(preparedChatTurn.userId());
        log.setAgentId(preparedChatTurn.agentId());
        log.setSessionId(preparedChatTurn.sessionId());
        log.setUserMessageId(preparedChatTurn.userMessageId());
        return log;
    }

    /**
     * 截断过长错误信息，避免超过数据库字段长度。
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Long normalizeCredentialId(com.enjoy.agent.modelgateway.domain.enums.CredentialSource credentialSource, Long credentialId) {
        if (credentialSource == com.enjoy.agent.modelgateway.domain.enums.CredentialSource.PLATFORM) {
            return null;
        }
        return credentialId;
    }
}
