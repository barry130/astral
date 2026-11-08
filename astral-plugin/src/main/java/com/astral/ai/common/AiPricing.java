package com.astral.ai.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/** Model price validation shared by configuration and immutable stage snapshots. */
public final class AiPricing {
    private AiPricing() {}

    public static String validate(BigDecimal input, BigDecimal output, String currency) {
        if ((input != null && input.signum() < 0) || (output != null && output.signum() < 0)) {
            throw new AiException("AI005", "模型单价不能为负数");
        }
        String normalized =
                currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new AiException("AI005", "模型币种须为有效的 ISO 4217 编码");
        }
        return normalized;
    }
}
