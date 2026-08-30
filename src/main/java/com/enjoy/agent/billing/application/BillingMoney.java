package com.enjoy.agent.billing.application;

import com.enjoy.agent.shared.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;

/** Exact DECIMAL(18,6) boundary used by every wallet mutation before persistence. */
final class BillingMoney {

    static final int SCALE = 6;
    static final int PRECISION = 18;

    private BillingMoney() {
    }

    static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            throw invalid("Billing amount is required");
        }
        final BigDecimal normalized;
        try {
            normalized = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalid("Billing amount must have at most six fractional digits");
        }
        if (normalized.precision() > PRECISION) {
            throw invalid("Billing amount exceeds DECIMAL(18,6) storage capacity");
        }
        return normalized;
    }

    static BigDecimal add(BigDecimal left, BigDecimal right) {
        return normalize(normalize(left).add(normalize(right)));
    }

    static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return normalize(normalize(left).subtract(normalize(right)));
    }

    private static ApiException invalid(String message) {
        return new ApiException(
                "BILLING_AMOUNT_PRECISION_EXCEEDED",
                message,
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }
}
