package com.huidu.musicboxplus.core.player;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VolumeManagerSaveFailureTest {
    @Test
    void rejectedSaveReleasesInProgressAndRetainsDirtySnapshotForRetry() {
        VolumeManager manager = new VolumeManager(false);
        UUID uuid = UUID.randomUUID();
        manager.cacheExplicitVolume(uuid, 100);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Map<UUID, Integer>> written = new AtomicReference<>();

        manager.saveDirtyVolumes((task, failure) -> {
            attempts.incrementAndGet();
            failure.run(); // same callback used by AsyncTaskManager on rejection/shutdown
        }, volumes -> fail("rejected task must never write"));

        manager.saveDirtyVolumes((task, failure) -> {
            attempts.incrementAndGet();
            task.run();
        }, written::set);

        assertEquals(2, attempts.get(), "rejection must not leave saveInProgress stuck true");
        assertEquals(Map.of(uuid, 100), written.get(), "dirty data must survive rejection");
        manager.saveDirtyVolumes((task, failure) -> fail("committed version must not save again"),
                volumes -> fail("nothing new to save"));
    }

    @Test
    void failureCallbackAfterAcceptedTaskReleasesSaveForRetry() {
        VolumeManager manager = new VolumeManager(false);
        UUID uuid = UUID.randomUUID();
        manager.cacheExplicitVolume(uuid, 75);
        AtomicReference<Runnable> onFailure = new AtomicReference<>();
        AtomicReference<Map<UUID, Integer>> written = new AtomicReference<>();

        manager.saveDirtyVolumes((task, failure) -> onFailure.set(failure),
                volumes -> fail("first task has not run"));
        manager.saveDirtyVolumes((task, failure) -> fail("only one in-flight save is allowed"),
                volumes -> fail("still in flight"));
        onFailure.get().run();
        manager.saveDirtyVolumes((task, failure) -> task.run(), written::set);

        assertEquals(Map.of(uuid, 75), written.get());
    }
}
