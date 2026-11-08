package com.huidu.musicboxplus.module.edit.publish;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RevenueClaimTest {
    @Test
    void successfulClaimLeavesConcurrentRevenueForTheNextClaim() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        AtomicInteger saves = new AtomicInteger();

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            assertEquals(100.0, amount);
            pending.merge(uuid, 25.0, Double::sum);
            return true;
        }, saves::incrementAndGet);

        assertEquals(RevenueClaim.Status.PAID, result.status());
        assertEquals(100.0, result.paidAmount());
        assertEquals(25.0, pending.get(uuid));
        assertEquals(1, saves.get());
        assertEquals(25.0, claims.claim(uuid, amount -> true, () -> {}).paidAmount());
        assertFalse(pending.containsKey(uuid));
    }

    @Test
    void definiteRefusalRestoresCapturedAmountAndConcurrentRevenueAndCanRetry() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        AtomicInteger saves = new AtomicInteger();

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            pending.merge(uuid, 25.0, Double::sum);
            return false;
        }, saves::incrementAndGet);

        assertEquals(RevenueClaim.Status.DECLINED, result.status());
        assertEquals(0.0, result.paidAmount());
        assertEquals(125.0, pending.get(uuid));
        assertEquals(1, saves.get(), "restore must save after a concurrent save may have observed removal");
        assertEquals(125.0, claims.claim(uuid, amount -> true, () -> {}).paidAmount());
    }

    @Test
    void providerExceptionPreservesTheBalanceButFreezesAnUnknownPaymentForManualReview() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        RuntimeException providerFailure = new IllegalStateException("may have credited before throwing");
        AtomicInteger payments = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            payments.incrementAndGet();
            pending.merge(uuid, 25.0, Double::sum);
            throw providerFailure;
        }, saves::incrementAndGet);

        assertEquals(RevenueClaim.Status.UNCERTAIN, result.status());
        assertSame(providerFailure, result.paymentFailure());
        assertEquals(100.0, result.amount());
        assertEquals(0.0, result.paidAmount());
        assertEquals(125.0, pending.get(uuid));
        assertEquals(1, saves.get());
        pending.merge(uuid, 10.0, Double::sum);
        RevenueClaim.Result retry = claims.claim(uuid, amount -> {
            payments.incrementAndGet();
            return true;
        }, saves::incrementAndGet);
        assertEquals(RevenueClaim.Status.UNCERTAIN, retry.status());
        assertSame(providerFailure, retry.paymentFailure());
        assertEquals(135.0, pending.get(uuid));
        assertEquals(1, payments.get(), "unknown payments must never be retried automatically");
        assertEquals(1, saves.get());
    }

    @Test
    void onlyOneClaimPerUuidCanPayWhileOtherAuthorsRemainIndependent() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        pending.put(other, 7.0);
        RevenueClaim claims = new RevenueClaim(pending);
        CountDownLatch paying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger payments = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<RevenueClaim.Result> first = executor.submit(() -> claims.claim(uuid, amount -> {
            payments.incrementAndGet();
            paying.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "test did not release the provider");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return true;
        }, () -> {}));
        try {
            assertTrue(paying.await(5, TimeUnit.SECONDS));
            pending.merge(uuid, 25.0, Double::sum);
            RevenueClaim.Result second = claims.claim(uuid, amount -> {
                payments.incrementAndGet();
                return true;
            }, () -> fail("busy claim must not persist"));
            assertEquals(RevenueClaim.Status.BUSY, second.status());
            assertEquals(7.0, claims.claim(other, amount -> true, () -> {}).paidAmount());
            release.countDown();
            assertEquals(100.0, first.get(5, TimeUnit.SECONDS).paidAmount());
            assertEquals(1, payments.get());
            assertEquals(25.0, pending.get(uuid));
        } finally {
            release.countDown();
            first.cancel(true);
            executor.shutdownNow();
        }
    }

    @Test
    void reentrantClaimCannotTakeConcurrentRevenue() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            pending.merge(uuid, 25.0, Double::sum);
            assertEquals(RevenueClaim.Status.BUSY,
                    claims.claim(uuid, ignored -> fail("reentrant payment"), () -> {}).status());
            return true;
        }, () -> {});

        assertEquals(100.0, result.paidAmount());
        assertEquals(25.0, pending.get(uuid));
    }

    @Test
    void invalidAmountsAreNotPaidOrRemovedOrSilentlyTreatedAsEmpty() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1.0}) {
            UUID uuid = UUID.randomUUID();
            ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
            pending.put(uuid, invalid);
            RevenueClaim claims = new RevenueClaim(pending);

            RevenueClaim.Result result = claims.claim(uuid, amount -> fail("invalid payment"),
                    () -> fail("invalid claim must leave the record untouched"));

            assertEquals(RevenueClaim.Status.INVALID, result.status());
            assertEquals(invalid, result.amount());
            assertTrue(pending.containsKey(uuid));
            assertEquals(invalid, pending.get(uuid));
            assertEquals(0.0, result.paidAmount());
        }
    }

    @Test
    void emptyAndZeroClaimsDoNotPayOrPersist() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        RevenueClaim claims = new RevenueClaim(pending);
        assertEquals(RevenueClaim.Status.EMPTY,
                claims.claim(uuid, amount -> fail("empty payment"), () -> fail("empty save")).status());
        pending.put(uuid, 0.0);
        assertEquals(RevenueClaim.Status.EMPTY,
                claims.claim(uuid, amount -> fail("zero payment"), () -> fail("zero save")).status());
        assertEquals(0.0, pending.get(uuid));
    }

    @Test
    void successfulPaymentIsNeverRestoredWhenPersistenceThrows() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        RuntimeException saveFailure = new IllegalStateException("disk unavailable");
        AtomicInteger payments = new AtomicInteger();

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            payments.incrementAndGet();
            return true;
        }, () -> { throw saveFailure; });

        assertEquals(RevenueClaim.Status.PAID, result.status());
        assertEquals(100.0, result.paidAmount());
        assertNull(result.paymentFailure());
        assertSame(saveFailure, result.persistenceFailure());
        assertFalse(pending.containsKey(uuid));
        assertEquals(RevenueClaim.Status.EMPTY, claims.claim(uuid, amount -> {
            payments.incrementAndGet();
            return true;
        }, () -> {}).status());
        assertEquals(1, payments.get());
    }

    @Test
    void refusalStillRestoresConcurrentRevenueWhenPersistenceThrows() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        RuntimeException saveFailure = new IllegalStateException("disk unavailable");

        RevenueClaim.Result result = claims.claim(uuid, amount -> {
            pending.merge(uuid, 25.0, Double::sum);
            return false;
        }, () -> { throw saveFailure; });

        assertEquals(RevenueClaim.Status.DECLINED, result.status());
        assertSame(saveFailure, result.persistenceFailure());
        assertEquals(125.0, pending.get(uuid));
        assertEquals(125.0, claims.claim(uuid, amount -> true, () -> {}).paidAmount());
    }

    @Test
    void persistenceFailureDoesNotUnfreezeAnUnknownPayment() {
        UUID uuid = UUID.randomUUID();
        ConcurrentMap<UUID, Double> pending = new ConcurrentHashMap<>();
        pending.put(uuid, 100.0);
        RevenueClaim claims = new RevenueClaim(pending);
        RuntimeException providerFailure = new IllegalStateException("payment status unknown");
        RuntimeException saveFailure = new IllegalStateException("disk unavailable");

        RevenueClaim.Result result = claims.claim(uuid, amount -> { throw providerFailure; },
                () -> { throw saveFailure; });

        assertEquals(RevenueClaim.Status.UNCERTAIN, result.status());
        assertSame(providerFailure, result.paymentFailure());
        assertSame(saveFailure, result.persistenceFailure());
        assertEquals(100.0, pending.get(uuid));
        assertEquals(RevenueClaim.Status.UNCERTAIN,
                claims.claim(uuid, amount -> fail("unknown payment must stay frozen"), () -> {}).status());
    }
}
