package com.huidu.musicboxplus.common.utils;

/**
 * Purchase decision table for the Vault economy, kept free of Bukkit so it can be unit tested.
 *
 * The important distinction is between the two ways money can be absent. A server that turns the
 * economy off is in FREE: discs are deliberately free. A server that turns the economy
 * ON but has no Vault provider registered is in UNAVAILABLE: nobody can be charged, so
 * a purchase must be refused rather than silently treated as free. Folding the two together is what
 * let a priced published song be handed out for nothing while the author still accrued revenue.
 */
public final class EconomyGate {

    public enum Mode {
        /** Economy disabled by config: prices are zero and purchases are free on purpose. */
        FREE,
        /** Economy enabled, but no provider resolved: refuse charged purchases. */
        UNAVAILABLE,
        /** Economy enabled and backed by a provider: charge normally. */
        ACTIVE
    }

    public static Mode mode(boolean configEnabled, boolean providerPresent) {
        if (!configEnabled) {
            return Mode.FREE;
        }
        return providerPresent ? Mode.ACTIVE : Mode.UNAVAILABLE;
    }

    /** False only when a charge is required but cannot be made. */
    public static boolean allowsPurchase(Mode mode) {
        return mode != Mode.UNAVAILABLE;
    }

    /** Only an active provider actually moves money out of the buyer's balance. */
    public static boolean requiresWithdrawal(Mode mode) {
        return mode == Mode.ACTIVE;
    }

    /** What the buyer is actually asked to pay: free mode is free, everything else shows the price. */
    public static double price(Mode mode, double configuredPrice) {
        return mode == Mode.FREE ? 0.0 : Math.max(0.0, configuredPrice);
    }

    public static boolean canAfford(Mode mode, double balance, double price) {
        return switch (mode) {
            case FREE -> true;
            // Not a balance problem, but "can this proceed" is false for the same call sites.
            case UNAVAILABLE -> false;
            // A NaN or infinite configured price must not pass an affordability check.
            case ACTIVE -> Double.isFinite(price) && balance - price >= 0.0;
        };
    }

    /** Amount the buyer is short by, for the refusal message. */
    public static double shortfall(double balance, double price) {
        double remaining = price - balance;
        return Double.isFinite(remaining) && remaining > 0.0 ? remaining : 0.0;
    }

    private EconomyGate() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
