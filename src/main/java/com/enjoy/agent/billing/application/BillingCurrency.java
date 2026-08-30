package com.enjoy.agent.billing.application;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical currency comparison for billing boundaries.
 */
public final class BillingCurrency {

    private BillingCurrency() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static boolean matches(String left, String right) {
        String normalizedLeft = normalize(left);
        return normalizedLeft != null && Objects.equals(normalizedLeft, normalize(right));
    }
}
