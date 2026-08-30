package com.enjoy.agent.billing.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.enjoy.agent.billing.application.BillingUsageApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class BillingUsageEventListenerTest {

    @Test
    void malformedPayloadShouldBeRejectedWithoutInfiniteRedelivery() {
        BillingUsageApplicationService service = mock(BillingUsageApplicationService.class);
        BillingUsageEventListener listener = new BillingUsageEventListener(service);

        assertThatThrownBy(() -> listener.onUsageEvent("not-a-number"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Invalid");

        verify(service, never()).processUsageEvent(org.mockito.ArgumentMatchers.any());
    }
}
