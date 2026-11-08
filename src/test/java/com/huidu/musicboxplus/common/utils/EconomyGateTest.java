package com.huidu.musicboxplus.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The purchase decision table. The case that matters is the middle one: an economy that is switched
// on but has no Vault provider must refuse a paid purchase instead of behaving like "free mode" --
// that fold is what handed out priced songs for nothing while the author still earned revenue.
class EconomyGateTest {

    @Test
    void configDisabledIsTheOnlyFreeMode() {
        assertEquals(EconomyGate.Mode.FREE, EconomyGate.mode(false, false));
        assertEquals(EconomyGate.Mode.FREE, EconomyGate.mode(false, true),
                "a disabled economy stays free even if a provider happens to be registered");
        assertEquals(EconomyGate.Mode.UNAVAILABLE, EconomyGate.mode(true, false));
        assertEquals(EconomyGate.Mode.ACTIVE, EconomyGate.mode(true, true));
    }

    @Test
    void enabledWithoutAProviderRefusesAPaidPurchase() {
        EconomyGate.Mode mode = EconomyGate.mode(true, false);

        assertFalse(EconomyGate.allowsPurchase(mode), "no provider means nobody can be charged");
        assertFalse(EconomyGate.requiresWithdrawal(mode), "and nothing may be withdrawn either");
        assertFalse(EconomyGate.canAfford(mode, 1_000_000.0, 10.0),
                "affordability must not turn an unavailable economy into a free purchase");
    }

    @Test
    void freeModeIsFreeAndAffordableWithoutMoney() {
        EconomyGate.Mode mode = EconomyGate.mode(false, false);

        assertEquals(0.0, EconomyGate.price(mode, 250.0), "free mode charges nothing even if a price is configured");
        assertTrue(EconomyGate.allowsPurchase(mode));
        assertTrue(EconomyGate.canAfford(mode, 0.0, 250.0));
        assertFalse(EconomyGate.requiresWithdrawal(mode));
    }

    @Test
    void activeModeComputesTheRealShortfall() {
        EconomyGate.Mode mode = EconomyGate.mode(true, true);

        assertEquals(100.0, EconomyGate.price(mode, 100.0));
        assertEquals(0.0, EconomyGate.price(mode, -5.0), "a negative price is never charged");
        assertTrue(EconomyGate.requiresWithdrawal(mode));
        assertTrue(EconomyGate.canAfford(mode, 100.0, 100.0), "exactly enough money must pass");
        assertFalse(EconomyGate.canAfford(mode, 99.99, 100.0));
        assertEquals(0.01, EconomyGate.shortfall(99.99, 100.0), 1e-9);
        assertEquals(0.0, EconomyGate.shortfall(200.0, 100.0));
    }

    @Test
    void nonFinitePricesAreRefusedInsteadOfPassingTheBalanceCheck() {
        EconomyGate.Mode mode = EconomyGate.mode(true, true);

        assertFalse(EconomyGate.canAfford(mode, 1_000_000.0, Double.NaN),
                "NaN - price comparisons are false, so a naive check would let it through");
        assertFalse(EconomyGate.canAfford(mode, 1_000_000.0, Double.POSITIVE_INFINITY),
                "an infinite price is not a payable amount");
    }
}
