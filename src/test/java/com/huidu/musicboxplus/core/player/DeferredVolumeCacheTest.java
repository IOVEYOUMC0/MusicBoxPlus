package com.huidu.musicboxplus.core.player;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class DeferredVolumeCacheTest {
    private static final int DEFAULT_VOLUME = 100;
    private static final BiConsumer<Runnable, Runnable> INLINE = (task, failure) -> task.run();

    @Test
    void cacheHitDoesNotTouchDispatcherReaderOrFailureHandler() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        cache.put(uuid, 1000); // outside the usual Integer cache
        Integer cached = cache.get(uuid);

        assertEquals(1000, cache.resolve(uuid, DEFAULT_VOLUME, null, null, null));
        assertSame(cached, cache.get(uuid), "cache hit must reuse the boxed value");
    }

    @Test
    void repeatedMissesShareOneQueuedLoad() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        BiConsumer<Runnable, Runnable> dispatcher = (task, failure) -> {
            submissions.incrementAndGet();
            queued.set(task);
        };
        cache.resolve(uuid, DEFAULT_VOLUME, dispatcher, u -> 45, e -> fail(e));
        cache.resolve(uuid, DEFAULT_VOLUME, dispatcher, u -> 70, e -> fail(e));

        assertEquals(1, submissions.get());
        queued.get().run();
        assertEquals(Map.of(uuid, 45), cache.snapshotForSave());
    }

    @Test
    void pendingAndFailedReadCannotPersistDefaultOverUnreadStoredValue() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        RuntimeException unavailable = new RuntimeException("query failed");

        assertEquals(DEFAULT_VOLUME, cache.resolve(uuid, DEFAULT_VOLUME,
                (task, rejected) -> queued.set(task), u -> { throw unavailable; }, failure::set));
        assertTrue(cache.snapshotForSave().isEmpty(), "in-flight default is not trustworthy either");
        queued.get().run();

        assertSame(unavailable, failure.get());
        assertEquals(DEFAULT_VOLUME, cache.get(uuid));
        assertFalse(cache.isPersistable(uuid));
        assertTrue(cache.snapshotForSave().isEmpty());
    }

    @Test
    void successfulReadAndMissingRowBecomePersistable() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID stored = UUID.randomUUID();
        UUID absent = UUID.randomUUID();

        cache.resolve(stored, DEFAULT_VOLUME, INLINE, u -> 45, e -> fail(e));
        cache.resolve(absent, DEFAULT_VOLUME, INLINE, u -> null, e -> fail(e));

        assertEquals(Map.of(stored, 45, absent, DEFAULT_VOLUME), cache.snapshotForSave());
    }

    @Test
    void explicitDefaultSurvivesLateSuccessfulRead() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        cache.resolve(uuid, DEFAULT_VOLUME, (task, failure) -> queued.set(task), u -> 45, e -> fail(e));

        cache.put(uuid, DEFAULT_VOLUME);
        queued.get().run();

        assertEquals(DEFAULT_VOLUME, cache.get(uuid));
        assertEquals(Map.of(uuid, DEFAULT_VOLUME), cache.snapshotForSave());
    }

    @Test
    void explicitDefaultBeforeLateReadFailureStaysPersistable() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        cache.resolve(uuid, DEFAULT_VOLUME, (task, failure) -> queued.set(task),
                u -> { throw new IllegalStateException("query failed"); }, e -> failures.incrementAndGet());

        cache.put(uuid, DEFAULT_VOLUME);
        queued.get().run();

        assertEquals(1, failures.get());
        assertEquals(Map.of(uuid, DEFAULT_VOLUME), cache.snapshotForSave(),
                "late failure must not mark an explicit default as an unread placeholder");
    }

    @Test
    void explicitDefaultAfterReadFailureRestoresPersistence() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        cache.resolve(uuid, DEFAULT_VOLUME, INLINE,
                u -> { throw new IllegalStateException("query failed"); }, e -> {});
        assertTrue(cache.snapshotForSave().isEmpty());

        cache.put(uuid, DEFAULT_VOLUME);

        assertEquals(Map.of(uuid, DEFAULT_VOLUME), cache.snapshotForSave());
    }

    @Test
    void rejectedLoadKeepsPlaceholderUntrustedWithoutBlockingExplicitSet() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        cache.resolve(uuid, DEFAULT_VOLUME, (task, failure) -> failure.run(),
                u -> { fail("rejected load must not read DB"); return null; }, e -> fail(e));
        assertTrue(cache.snapshotForSave().isEmpty());

        cache.put(uuid, DEFAULT_VOLUME);

        assertEquals(Map.of(uuid, DEFAULT_VOLUME), cache.snapshotForSave());
    }

    @Test
    void evictedLoadCannotOverwriteNewPlaceholderWithSameDefault() {
        VolumeManager.DeferredVolumeCache cache = new VolumeManager.DeferredVolumeCache();
        UUID uuid = UUID.randomUUID();
        AtomicReference<Runnable> oldLoad = new AtomicReference<>();
        AtomicReference<Runnable> newLoad = new AtomicReference<>();
        cache.resolve(uuid, DEFAULT_VOLUME, (task, failure) -> oldLoad.set(task), u -> 45, e -> fail(e));
        cache.remove(uuid);
        cache.resolve(uuid, DEFAULT_VOLUME, (task, failure) -> newLoad.set(task), u -> 70, e -> fail(e));

        oldLoad.get().run();
        assertEquals(DEFAULT_VOLUME, cache.get(uuid));
        assertTrue(cache.snapshotForSave().isEmpty());
        newLoad.get().run();
        assertEquals(Map.of(uuid, 70), cache.snapshotForSave());
    }
}
