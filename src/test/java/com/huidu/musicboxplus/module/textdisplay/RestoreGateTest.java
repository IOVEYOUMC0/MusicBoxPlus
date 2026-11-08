package com.huidu.musicboxplus.module.textdisplay;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RestoreGateTest {
    @Test
    void concurrentRestoresCanClaimANameOnlyOnce() throws Exception {
        RestoreGate gate = new RestoreGate();
        long session = gate.generation();
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<CompletableFuture<RestoreGate.Token>> attempts = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            attempts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("start was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return gate.claim(session, "stage");
            }));
        }
        start.countDown();
        int winners = 0;
        for (CompletableFuture<RestoreGate.Token> attempt : attempts) {
            if (attempt.get(5, TimeUnit.SECONDS) != null) {
                winners++;
            }
        }
        assertEquals(1, winners);
    }

    @Test
    void inlineWarmAndIdleStartsStillWaitForTheirRegion() {
        // whenReady's warm AND idle branches both call start inline. This is the actual production
        // gate, with a recording region dispatcher in place of Bukkit's scheduler.
        for (String branch : new String[]{"warm", "idle"}) {
            RestoreGate gate = new RestoreGate();
            RestoreGate.Token token = gate.claim(gate.generation(), branch);
            Queue<Runnable> region = new ArrayDeque<>();
            AtomicInteger spawned = new AtomicInteger();

            gate.whenReady(token, Runnable::run, region::add, spawned::incrementAndGet);

            assertEquals(0, spawned.get(), branch + " must not touch chunks on the caller thread");
            assertEquals(1, region.size());
            assertNull(gate.claim(gate.generation(), branch), "queued region work still owns its name");
            region.remove().run();
            assertEquals(1, spawned.get());
            assertNotNull(gate.claim(gate.generation(), branch));
        }
    }

    @Test
    void coldStartRemainsReservedThroughCompilationAndRegionQueue() {
        RestoreGate gate = new RestoreGate();
        long session = gate.generation();
        RestoreGate.Token token = gate.claim(session, "stage");
        Queue<Runnable> compilation = new ArrayDeque<>();
        Queue<Runnable> region = new ArrayDeque<>();
        AtomicInteger spawned = new AtomicInteger();

        gate.whenReady(token, compilation::add, region::add, spawned::incrementAndGet);

        assertNull(gate.claim(session, "stage"), "a second restoreAll must not reset a live claim");
        assertEquals(0, region.size());
        compilation.remove().run();
        assertEquals(0, spawned.get());
        assertNull(gate.claim(session, "stage"), "compilation finishing does not release the name");
        Runnable commit = region.remove();
        commit.run();
        commit.run();
        assertEquals(1, spawned.get(), "even a repeated callback cannot spawn twice");
    }

    @Test
    void queuedOldGenerationCannotSpawnOrReleaseNewGenerationToken() {
        RestoreGate gate = new RestoreGate();
        long oldSession = gate.generation();
        RestoreGate.Token old = gate.claim(oldSession, "stage");
        Queue<Runnable> region = new ArrayDeque<>();
        AtomicInteger spawned = new AtomicInteger();
        gate.whenReady(old, Runnable::run, region::add, spawned::incrementAndGet);

        gate.closeSession();
        assertNull(gate.claim(gate.generation(), "stage"), "disabled sessions reject new work");
        gate.resetSession();
        RestoreGate.Token current = gate.claim(gate.generation(), "stage");
        assertNotNull(current, "a dropped task must not strand a subsequent enabled session");
        assertNull(gate.claim(oldSession, "other"), "an old restoreAll cannot claim in the new session");

        region.remove().run();
        gate.complete(old);

        assertEquals(0, spawned.get());
        assertTrue(gate.isCurrent(current));
        assertNull(gate.claim(gate.generation(), "stage"), "old completion must not remove the new token");
        gate.whenReady(current, Runnable::run, region::add, spawned::incrementAndGet);
        region.remove().run();
        assertEquals(1, spawned.get());
    }

    @Test
    void oldCompilationCompletionCannotEvenEnqueueARegionTask() {
        RestoreGate gate = new RestoreGate();
        RestoreGate.Token old = gate.claim(gate.generation(), "stage");
        Queue<Runnable> compilation = new ArrayDeque<>();
        Queue<Runnable> region = new ArrayDeque<>();
        gate.whenReady(old, compilation::add, region::add, () -> fail("stale restore"));

        gate.resetSession();
        RestoreGate.Token current = gate.claim(gate.generation(), "stage");
        compilation.remove().run();

        assertTrue(region.isEmpty());
        assertTrue(gate.isCurrent(current));
    }

    @Test
    void rejectedSchedulingAndFailedSpawnReleaseOnlyTheirOwnToken() {
        for (boolean rejectScheduling : new boolean[]{true, false}) {
            RestoreGate gate = new RestoreGate();
            RestoreGate.Token token = gate.claim(gate.generation(), "stage");
            Consumer<Runnable> region = rejectScheduling
                    ? run -> { throw new IllegalStateException("scheduler rejected"); }
                    : Runnable::run;

            assertThrows(IllegalStateException.class, () -> gate.whenReady(token, Runnable::run,
                    region, () -> { throw new IllegalStateException("spawn failed"); }));

            assertNotNull(gate.claim(gate.generation(), "stage"));
        }
    }

    @Test
    void cancellingADeletedNameInvalidatesItsQueuedRestore() {
        RestoreGate gate = new RestoreGate();
        RestoreGate.Token old = gate.claim(gate.generation(), "stage");
        Queue<Runnable> region = new ArrayDeque<>();
        gate.whenReady(old, Runnable::run, region::add, () -> fail("deleted fixture restored"));
        gate.cancel("stage");
        RestoreGate.Token replacement = gate.claim(gate.generation(), "stage");

        region.remove().run();
        gate.complete(old);

        assertTrue(gate.isCurrent(replacement));
    }
}
