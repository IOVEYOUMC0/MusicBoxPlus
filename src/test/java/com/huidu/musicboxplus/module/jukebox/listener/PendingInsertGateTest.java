package com.huidu.musicboxplus.module.jukebox.listener;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the same lease gate used by JukeboxHopperListener, not a test-only guard model. */
class PendingInsertGateTest {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(5);

    @Test
    void concurrentNormalInsertsHaveExactlyOneClaim() throws Exception {
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, () -> 0L);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<PendingInsertGate.Token<String>>> attempts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            attempts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("start was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return gate.acquire("jukebox");
            }));
        }
        start.countDown();
        List<PendingInsertGate.Token<String>> winners = new ArrayList<>();
        for (CompletableFuture<PendingInsertGate.Token<String>> attempt : attempts) {
            PendingInsertGate.Token<String> token = attempt.get(5, TimeUnit.SECONDS);
            if (token != null) {
                winners.add(token);
            }
        }
        assertEquals(1, winners.size());
        AtomicInteger mutations = new AtomicInteger();
        assertTrue(gate.runIfCurrent(winners.get(0), mutations::incrementAndGet));
        assertEquals(1, mutations.get());
        gate.release(winners.get(0));
        assertNotNull(gate.acquire("jukebox"));
    }

    @Test
    void fiveSecondExpiryAllowsTakeoverAndOldCompletionCannotReleaseIt() {
        AtomicLong now = new AtomicLong();
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, now::get);
        PendingInsertGate.Token<String> old = gate.acquire("jukebox");
        now.set(TIMEOUT);
        assertNull(gate.acquire("jukebox"), "preserve the existing strictly-older-than timeout");
        now.incrementAndGet();
        PendingInsertGate.Token<String> replacement = gate.acquire("jukebox");
        assertNotNull(replacement);
        assertNotSame(old, replacement);
        assertFalse(gate.isCurrent(old));

        // Completion, source-retired and exception callbacks may all arrive after takeover.
        gate.release(old);
        gate.release(old);
        assertTrue(gate.isCurrent(replacement));
        assertNull(gate.acquire("jukebox"));
        assertFalse(gate.runIfCurrent(old, () -> fail("old source must not remove a disc")));
    }

    @Test
    void resetInvalidatesQueuedTokensEvenWhenClockHasNotAdvanced() {
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, () -> 0L);
        PendingInsertGate.Token<String> old = gate.acquire("jukebox");
        gate.reset();
        assertFalse(gate.runIfCurrent(old, () -> fail("old session mutated inventory")));
        PendingInsertGate.Token<String> replacement = gate.acquire("jukebox");
        assertNotNull(replacement);
        assertNotSame(old, replacement, "timestamp equality is not lease identity");
        gate.release(old);
        assertTrue(gate.isCurrent(replacement));
        assertNull(gate.acquire("jukebox"));
    }

    @Test
    void expiredTaskCannotRemoveFromSourceEvenBeforeAnotherInsertTakesOver() {
        AtomicLong now = new AtomicLong();
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, now::get);
        PendingInsertGate.Token<String> token = gate.acquire("jukebox");
        now.set(TIMEOUT + 1);
        assertFalse(gate.runIfCurrent(token, () -> fail("expired source task took a disc")));
        assertNotNull(gate.acquire("jukebox"));
    }

    @Test
    void expiryAfterSourceRemovalRejectsTargetMutationWithoutReleasingReplacement() {
        AtomicLong now = new AtomicLong();
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, now::get);
        PendingInsertGate.Token<String> old = gate.acquire("jukebox");
        AtomicInteger sourceDiscs = new AtomicInteger(1);
        assertTrue(gate.runIfCurrent(old, sourceDiscs::decrementAndGet));
        assertEquals(0, sourceDiscs.get());
        now.set(TIMEOUT + 1);
        PendingInsertGate.Token<String> replacement = gate.acquire("jukebox");
        assertFalse(gate.runIfCurrent(old, () -> fail("expired target inserted the removed disc")));
        gate.release(old);
        assertTrue(gate.isCurrent(replacement), "restitution completion must not unlock a new transfer");
    }

    @Test
    void failedMutationReleasesOnlyItsOwnLeaseAndCanRecover() {
        PendingInsertGate<String> gate = new PendingInsertGate<>(TIMEOUT, () -> 0L);
        PendingInsertGate.Token<String> token = gate.acquire("jukebox");
        assertThrows(IllegalStateException.class, () -> gate.runIfCurrent(token, () -> {
            throw new IllegalStateException("inventory failure");
        }));
        gate.release(token);
        PendingInsertGate.Token<String> replacement = gate.acquire("jukebox");
        assertNotNull(replacement);
        gate.release(token);
        assertTrue(gate.isCurrent(replacement));
    }
}
