package com.huidu.musicboxplus.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Regression tests for VolumeManager.resolveVolumeWithDeferredLoad, the extracted core of
// getVolume(UUID). Invariant under test: getVolume inserts a placeholder default and dispatches
// an async DB load, and that DB value may only overwrite the placeholder while the placeholder is
// still in the map -- if setVolume landed in between, the stale DB value must be dropped instead
// of clobbering it. A latch-gated executor makes both sides of the race deterministic.
class VolumeManagerRaceTest {

    private static final int DEFAULT_VOLUME = 100;

    private ExecutorService asyncPool;
    private Map<UUID, Integer> playerVolumes;

    @BeforeEach
    void setUp() {
        // Two threads so we can pile work into the async path without deadlocking the test thread.
        asyncPool = Executors.newFixedThreadPool(2);
        playerVolumes = new ConcurrentHashMap<>();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        asyncPool.shutdownNow();
        assertTrue(asyncPool.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void cachedValueShortCircuitsWithoutTouchingDb() {
        UUID uuid = UUID.randomUUID();
        playerVolumes.put(uuid, 42);

        AtomicInteger dbCalls = new AtomicInteger();
        AtomicInteger onLoadedCalls = new AtomicInteger();
        Function<UUID, Integer> dbReader = u -> {
            dbCalls.incrementAndGet();
            return 999;
        };
        Consumer<Integer> onLoaded = v -> onLoadedCalls.incrementAndGet();

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, asyncPool, dbReader, onLoaded);

        assertEquals(42, returned);
        assertEquals(42, playerVolumes.get(uuid));
        assertEquals(0, dbCalls.get(), "DB must not be queried when value is already cached");
        assertEquals(0, onLoadedCalls.get());
    }

    @Test
    void dbValueLandsWhenNoConcurrentSetVolume() throws Exception {
        UUID uuid = UUID.randomUUID();
        CountDownLatch loadedSignal = new CountDownLatch(1);
        AtomicReference<Integer> onLoadedValue = new AtomicReference<>();
        Function<UUID, Integer> dbReader = u -> 60;
        Consumer<Integer> onLoaded = v -> {
            onLoadedValue.set(v);
            loadedSignal.countDown();
        };

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, asyncPool, dbReader, onLoaded);

        assertEquals(DEFAULT_VOLUME, returned);
        assertTrue(loadedSignal.await(5, TimeUnit.SECONDS), "DB load must complete in time");
        assertEquals(60, onLoadedValue.get());
        assertEquals(60, playerVolumes.get(uuid));
    }

    @Test
    void setVolumeBetweenInsertAndDbLoadIsNotClobbered() throws Exception {
        UUID uuid = UUID.randomUUID();
        CountDownLatch dbHeld = new CountDownLatch(1);
        CountDownLatch userSetDone = new CountDownLatch(1);
        AtomicInteger onLoadedCalls = new AtomicInteger();

        Function<UUID, Integer> dbReader = u -> {
            try {
                // Wait until the test has had a chance to overwrite with setVolume(75).
                assertTrue(userSetDone.await(5, TimeUnit.SECONDS),
                        "test must overwrite before DB load returns");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return 50; // The "old" DB value that would have clobbered the user's 75.
        };
        Consumer<Integer> onLoaded = v -> {
            onLoadedCalls.incrementAndGet();
            dbHeld.countDown();
        };

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, asyncPool, dbReader, onLoaded);
        assertEquals(DEFAULT_VOLUME, returned);
        assertEquals(DEFAULT_VOLUME, playerVolumes.get(uuid),
                "placeholder must be inserted synchronously before async load");

        // Simulate user.setVolume(75) landing before the DB load completes.
        playerVolumes.put(uuid, 75);
        userSetDone.countDown();

        // Give the async path time to attempt its CAS-overwrite.
        Thread.sleep(150L);

        assertEquals(75, playerVolumes.get(uuid),
                "user's setVolume must NOT be overwritten by stale DB load");
        assertEquals(0, onLoadedCalls.get(),
                "onLoaded must not fire when CAS fails (no NoteBlockAPI desync)");
        assertEquals(1L, dbHeld.getCount(),
                "dbHeld latch must remain at 1 because onLoaded was skipped");
    }

    @Test
    void putIfAbsentRaceCollapsesToSingleSuccessfulInsert() throws Exception {
        // Concurrent callers for the same uuid: exactly one may insert and dispatch the async
        // load, the rest must observe the placeholder and bail.
        UUID uuid = UUID.randomUUID();
        AtomicInteger dbCalls = new AtomicInteger();
        AtomicInteger onLoadedCalls = new AtomicInteger();
        CountDownLatch loadedSignal = new CountDownLatch(1);
        Function<UUID, Integer> dbReader = u -> {
            dbCalls.incrementAndGet();
            return 80;
        };
        Consumer<Integer> onLoaded = v -> {
            onLoadedCalls.incrementAndGet();
            loadedSignal.countDown();
        };

        int threads = 8;
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch fire = new CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(callers.submit(() -> {
                    fire.await();
                    return VolumeManager.resolveVolumeWithDeferredLoad(
                            uuid, DEFAULT_VOLUME, playerVolumes, asyncPool, dbReader, onLoaded);
                }));
            }
            fire.countDown();
            for (java.util.concurrent.Future<Integer> f : futures) {
                int returned = f.get(5, TimeUnit.SECONDS);
                assertEquals(DEFAULT_VOLUME, returned,
                        "every concurrent caller must observe the same placeholder default");
            }
        } finally {
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(loadedSignal.await(5, TimeUnit.SECONDS), "DB load must run once");
        assertEquals(1, dbCalls.get(), "putIfAbsent must funnel all callers — only one DB read");
        assertEquals(1, onLoadedCalls.get(), "onLoaded must fire exactly once");
        assertEquals(80, playerVolumes.get(uuid));
    }

    @Test
    void dbReturningNullLeavesPlaceholderInPlace() throws Exception {
        UUID uuid = UUID.randomUUID();
        CountDownLatch dbDone = new CountDownLatch(1);
        AtomicInteger onLoadedCalls = new AtomicInteger();
        Function<UUID, Integer> dbReader = u -> {
            dbDone.countDown();
            return null; // No row in DB yet.
        };
        Consumer<Integer> onLoaded = v -> onLoadedCalls.incrementAndGet();

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, asyncPool, dbReader, onLoaded);

        assertEquals(DEFAULT_VOLUME, returned);
        assertTrue(dbDone.await(5, TimeUnit.SECONDS));
        // Small wait so the async path's null short-circuit has time to run.
        Thread.sleep(50L);
        assertEquals(DEFAULT_VOLUME, playerVolumes.get(uuid),
                "placeholder must remain when DB has no value");
        assertEquals(0, onLoadedCalls.get(), "onLoaded must not fire when DB has no value");
    }

    @Test
    void nullOnLoadedCallbackIsTolerated() throws Exception {
        UUID uuid = UUID.randomUUID();
        Function<UUID, Integer> dbReader = u -> 33;
        Executor synchronous = Runnable::run;

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, synchronous, dbReader, null);

        assertEquals(DEFAULT_VOLUME, returned);
        assertEquals(33, playerVolumes.get(uuid),
                "DB value must land even when onLoaded callback is null");
    }

    @Test
    void synchronousExecutorWritesValueBeforeCallReturns() {
        UUID uuid = UUID.randomUUID();
        Executor synchronous = Runnable::run;
        Function<UUID, Integer> dbReader = u -> 55;
        AtomicInteger onLoadedCalls = new AtomicInteger();
        Consumer<Integer> onLoaded = v -> onLoadedCalls.incrementAndGet();

        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, synchronous, dbReader, onLoaded);

        // The return value is the cached value as of return time, i.e. the placeholder default,
        // even though the load has already written the DB value into the map.
        assertEquals(DEFAULT_VOLUME, returned);
        assertEquals(55, playerVolumes.get(uuid));
        assertEquals(1, onLoadedCalls.get());
    }

    @Test
    void absentKeyHandlingAndUnusedVariableSilencer() {
        UUID uuid = UUID.randomUUID();
        assertNull(playerVolumes.get(uuid));
        int returned = VolumeManager.resolveVolumeWithDeferredLoad(
                uuid, DEFAULT_VOLUME, playerVolumes, Runnable::run, u -> null, v -> {});
        assertEquals(DEFAULT_VOLUME, returned);
        assertEquals(DEFAULT_VOLUME, playerVolumes.get(uuid));
    }
}
