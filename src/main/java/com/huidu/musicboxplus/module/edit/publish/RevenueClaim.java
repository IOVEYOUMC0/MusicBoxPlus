package com.huidu.musicboxplus.module.edit.publish;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoublePredicate;

/** Single-flight claims independent of Bukkit and the economy provider. */
final class RevenueClaim {
    private final ConcurrentMap<UUID, Double> pending;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, Result> uncertain = new ConcurrentHashMap<>();

    RevenueClaim(ConcurrentMap<UUID, Double> pending) {
        this.pending = Objects.requireNonNull(pending);
    }

    Result claim(UUID uuid, DoublePredicate payment, Runnable persist) {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(payment);
        Objects.requireNonNull(persist);
        if (!inFlight.add(uuid)) {
            return new Result(Status.BUSY, 0, null, null);
        }
        try {
            Result unresolved = uncertain.get(uuid);
            if (unresolved != null) {
                return unresolved;
            }

            AtomicReference<Double> captured = new AtomicReference<>();
            pending.computeIfPresent(uuid, (key, amount) -> {
                captured.set(amount);
                // Invalid records must remain visible instead of disappearing as an empty claim.
                return Double.isFinite(amount) && amount > 0 ? null : amount;
            });
            Double amount = captured.get();
            if (amount == null || amount == 0) {
                return new Result(Status.EMPTY, 0, null, null);
            }
            if (!Double.isFinite(amount) || amount < 0) {
                return new Result(Status.INVALID, amount, null, null);
            }

            final boolean paid;
            try {
                paid = payment.test(amount);
            } catch (RuntimeException failure) {
                // A provider may throw after crediting the account. Keep the debt for audit,
                // but freeze this UUID: restoring it as ordinarily retryable risks double pay.
                Result unresolvedPayment = new Result(Status.UNCERTAIN, amount, failure, null);
                uncertain.put(uuid, unresolvedPayment);
                pending.merge(uuid, amount, Double::sum);
                Result result = persist(unresolvedPayment, persist);
                uncertain.put(uuid, result);
                return result;
            }

            if (!paid) {
                // Only a definite refusal is safe to retry; preserve any concurrent sale.
                pending.merge(uuid, amount, Double::sum);
            }
            // Deliberately outside the provider catch. Once paid, a save failure cannot turn
            // this into a failed payment or restore funds which may then be paid a second time.
            return persist(new Result(paid ? Status.PAID : Status.DECLINED, amount, null, null), persist);
        } finally {
            inFlight.remove(uuid);
        }
    }

    private Result persist(Result result, Runnable persist) {
        try {
            persist.run();
            return result;
        } catch (RuntimeException failure) {
            return new Result(result.status(), result.amount(), result.paymentFailure(), failure);
        }
    }

    // This quarantine is intentionally process-local. The caller must report uncertain claims
    // for manual reconciliation before restart; the existing UUID -> double file has no state tag.
    enum Status { EMPTY, BUSY, INVALID, DECLINED, PAID, UNCERTAIN }

    record Result(Status status, double amount, RuntimeException paymentFailure,
                  RuntimeException persistenceFailure) {
        double paidAmount() {
            return status == Status.PAID ? amount : 0;
        }
    }
}
