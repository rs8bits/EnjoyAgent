package com.enjoy.agent.support.application;

import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import com.enjoy.agent.model.infrastructure.persistence.OfficialModelConfigRepository;
import com.enjoy.agent.modelgateway.application.PreparedModelConfig;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import com.enjoy.agent.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将显式配置的官方模型转换成客服运行时快照。
 *
 * <p>不“挑第一条可用模型”，因为数据库顺序会让不同环境行为不一致。规则回答不
 * 依赖本服务；只有真正需要 LLM 时才校验模型配置。</p>
 */
@Service
public class SupportModelResolver {

    private final SupportProperties properties;
    private final OfficialModelConfigRepository repository;

    public SupportModelResolver(SupportProperties properties, OfficialModelConfigRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PreparedSupportModel requireModel() {
        Long modelConfigId = properties.getOfficialModelConfigId();
        if (modelConfigId == null) {
            throw new ApiException(
                    "SUPPORT_MODEL_NOT_CONFIGURED",
                    "平台客服模型尚未配置，请联系管理员",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        OfficialModelConfig config = repository.findByIdAndEnabledTrue(modelConfigId)
                .orElseThrow(() -> new ApiException(
                        "SUPPORT_MODEL_NOT_AVAILABLE",
                        "平台客服模型不可用，请联系管理员",
                        HttpStatus.SERVICE_UNAVAILABLE
                ));
        if (config.getModelType() != ModelType.CHAT
                || config.getOfficialCredential() == null
                || !config.getOfficialCredential().isEnabled()) {
            throw new ApiException(
                    "SUPPORT_MODEL_NOT_AVAILABLE",
                    "平台客服模型不可用，请联系管理员",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }

        PreparedModelConfig snapshot = new PreparedModelConfig(
                config.getProvider(),
                config.getModelType(),
                config.getModelName(),
                CredentialSource.PLATFORM,
                null,
                config.getOfficialCredential().getSecretCiphertext(),
                config.getTemperature(),
                config.getMaxTokens() == null
                        ? properties.getMaxOutputTokens()
                        : Math.min(config.getMaxTokens(), properties.getMaxOutputTokens()),
                config.getId(),
                config.getOfficialCredential().getBaseUrl(),
                config.getInputPricePerMillion(),
                config.getOutputPricePerMillion(),
                config.getCurrency()
        );
        return new PreparedSupportModel(config.getId(), snapshot);
    }
}
