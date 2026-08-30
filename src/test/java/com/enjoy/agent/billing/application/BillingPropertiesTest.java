package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

    @Test
    void reservationAndProcessingBoundsShouldRejectUnsafeValues() {
        BillingProperties properties = new BillingProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReservationTokens(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReservationMaxInputTokens(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReservationMaxOutputTokens(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReservationTtl(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReservationReleaseClaimTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMinimumReservationAmount(new BigDecimal("-0.1")));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setProcessingMaxAttempts(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setProcessingRetryDelay(Duration.ofMillis(-1)));
    }

    @Test
    void outboxBoundsShouldRejectUnsafeValues() {
        BillingProperties.Rabbit rabbit = new BillingProperties.Rabbit();

        assertThatIllegalArgumentException().isThrownBy(() -> rabbit.setPublisherConfirmTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> rabbit.setOutboxClaimTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> rabbit.setOutboxPublishRetryDelay(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> rabbit.setReconciliationDelay(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> rabbit.setOutboxBatchSize(0));
    }
}
