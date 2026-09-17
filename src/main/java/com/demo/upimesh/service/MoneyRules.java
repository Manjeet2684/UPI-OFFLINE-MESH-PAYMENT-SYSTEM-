package com.demo.upimesh.service;

import java.math.BigDecimal;

public final class MoneyRules {

    private MoneyRules() {}

    public static String validateAmount(BigDecimal amount) {
        if (amount == null) {
            return "amount_required";
        }
        if (amount.signum() <= 0) {
            return "amount_not_positive";
        }
        if (amount.scale() > 2) {
            return "amount_scale";
        }
        return null;
    }
}
