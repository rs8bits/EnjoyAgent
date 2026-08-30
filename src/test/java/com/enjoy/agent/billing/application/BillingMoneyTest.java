package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BillingMoneyTest {

    @Test
    void maximumDecimalValueShouldRemainRepresentable() {
        assertThat(BillingMoney.normalize(new BigDecimal("999999999999.999999")))
                .isEqualByComparingTo("999999999999.999999");
    }

    @Test
    void carryBeyondDecimalPrecisionShouldFailAsControlledFinancialError() {
        assertThatThrownBy(() -> BillingMoney.add(
                new BigDecimal("999999999999.999999"),
                new BigDecimal("0.000001")
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BILLING_AMOUNT_PRECISION_EXCEEDED");
    }

    @Test
    void unrepresentableFractionShouldNeverReachTheDatabase() {
        assertThatThrownBy(() -> BillingMoney.normalize(new BigDecimal("0.0000001")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BILLING_AMOUNT_PRECISION_EXCEEDED");
    }
}
