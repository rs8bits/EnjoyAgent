package com.enjoy.agent.modelgateway.application;

import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.modelgateway.domain.entity.ModelCallLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原子记录一次成功模型调用的审计日志与官方模型用量事件。
 */
@Service
public class ModelCallAccountingService {

    private final ModelCallLogService modelCallLogService;
    private final BillingUsageApplicationService billingUsageApplicationService;

    public ModelCallAccountingService(
            ModelCallLogService modelCallLogService,
            BillingUsageApplicationService billingUsageApplicationService
    ) {
        this.modelCallLogService = modelCallLogService;
        this.billingUsageApplicationService = billingUsageApplicationService;
    }

    /**
     * 工具规划响应也属于一次真实模型调用，因此必须逐轮记录和计费。
     */
    @Transactional
    public void recordSuccess(
            PreparedChatTurn preparedChatTurn,
            ModelGatewayChatCompletion completion,
            Long reservationId
    ) {
        ModelCallLog modelCallLog = modelCallLogService.recordSuccess(preparedChatTurn, completion);
        billingUsageApplicationService.recordUsageEventIfNeeded(
                preparedChatTurn,
                completion.toResult(),
                modelCallLog.getId(),
                reservationId
        );
    }
}
