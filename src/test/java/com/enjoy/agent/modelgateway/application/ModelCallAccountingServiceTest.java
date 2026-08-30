package com.enjoy.agent.modelgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.credential.domain.enums.CredentialProvider;
import com.enjoy.agent.modelgateway.domain.entity.ModelCallLog;
import com.enjoy.agent.modelgateway.domain.enums.CredentialSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelCallAccountingServiceTest {

    @Test
    void recordSuccess_recordsLogAndUsageForTheSameModelRound() {
        ModelCallLogService modelCallLogService = mock(ModelCallLogService.class);
        BillingUsageApplicationService billingUsageApplicationService = mock(BillingUsageApplicationService.class);
        ModelCallAccountingService service = new ModelCallAccountingService(
                modelCallLogService,
                billingUsageApplicationService
        );
        PreparedChatTurn preparedChatTurn = mock(PreparedChatTurn.class);
        ModelGatewayChatCompletion completion = new ModelGatewayChatCompletion(
                null,
                List.of(new ModelGatewayToolCall("call-1", "demo", "{}")),
                CredentialProvider.OPENAI,
                "gpt-test",
                CredentialSource.PLATFORM,
                null,
                50L,
                12,
                3,
                15
        );
        ModelCallLog modelCallLog = mock(ModelCallLog.class);
        when(modelCallLog.getId()).thenReturn(99L);
        when(modelCallLogService.recordSuccess(preparedChatTurn, completion)).thenReturn(modelCallLog);

        service.recordSuccess(preparedChatTurn, completion, 77L);

        verify(modelCallLogService).recordSuccess(preparedChatTurn, completion);
        ArgumentCaptor<ModelGatewayResult> resultCaptor = ArgumentCaptor.forClass(ModelGatewayResult.class);
        verify(billingUsageApplicationService).recordUsageEventIfNeeded(
                org.mockito.ArgumentMatchers.eq(preparedChatTurn),
                resultCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq(77L)
        );
        assertThat(resultCaptor.getValue().promptTokens()).isEqualTo(12);
        assertThat(resultCaptor.getValue().completionTokens()).isEqualTo(3);
    }
}
