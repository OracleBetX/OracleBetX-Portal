package com.oraclebet.portal.settlement.utils;

import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SettlementFactorCalculator {

    private SettlementFactorCalculator() {}

    @Value
    public static class FactorResult {
        BigDecimal winnersProfit; // Σ max(pnlRaw,0)
        BigDecimal losersLoss;    // Σ -min(pnlRaw,0)
        BigDecimal factor;        // min(1, losersLoss / winnersProfit)
    }

    public static FactorResult computeFactor(BigDecimal winnersProfit, BigDecimal losersLoss) {
        winnersProfit = nvl(winnersProfit);
        losersLoss = nvl(losersLoss);

        if (winnersProfit.signum() <= 0) {
            return new FactorResult(winnersProfit, losersLoss, BigDecimal.ZERO);
        }

        // factor = min(1, losersLoss / winnersProfit)
        BigDecimal ratio = losersLoss.divide(winnersProfit, 18, RoundingMode.DOWN);
        BigDecimal factor = ratio.min(BigDecimal.ONE);

        return new FactorResult(winnersProfit, losersLoss, factor);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}