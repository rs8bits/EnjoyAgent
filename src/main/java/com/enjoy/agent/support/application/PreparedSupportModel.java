package com.enjoy.agent.support.application;

import com.enjoy.agent.modelgateway.application.PreparedModelConfig;

/** 客服官方模型快照及其可审计配置 ID。 */
public record PreparedSupportModel(Long officialModelConfigId, PreparedModelConfig modelConfig) {
}
