package com.lostglade.server;

import java.math.BigDecimal;

public final class AncientUkrCollectorRulesTest {
    public static void main(String[] args) {
        expect(AncientUkrCreditSystem.collectorWaveSize(0), 0, "No active credits");
        expect(AncientUkrCreditSystem.collectorWaveSize(1), 3, "One active credit");
        expect(AncientUkrCreditSystem.collectorWaveSize(3), 9, "Three active credits");
        require(!AncientUkrCreditSystem.collectorThresholdExceeded(100, BigDecimal.valueOf(100)),
                "Debt equal to principal must not summon collectors");
        require(AncientUkrCreditSystem.collectorThresholdExceeded(100, BigDecimal.valueOf(101)),
                "Debt above principal must summon collectors");
        System.out.println("Ancient Ukr collector rules tests passed");
    }

    private static void expect(int actual, int expected, String message) {
        if (actual != expected) throw new AssertionError(message + ": " + actual + " != " + expected);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
