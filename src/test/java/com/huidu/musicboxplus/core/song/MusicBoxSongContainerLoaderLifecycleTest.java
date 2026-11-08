package com.huidu.musicboxplus.core.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Container-level half of the loader lifecycle. ManagedSongLoaderTest already pins the helper's own
// rules (stale session cannot submit or publish, shutdown completes every tracked future); what only
// the container can show is that a shut-down loader really refuses whole loads instead of quietly
// building another pool, that a refusal publishes nothing, and that resetLoader() re-arms it.
class MusicBoxSongContainerLoaderLifecycleTest {
    @TempDir
    Path tempDir;

    private static ExecutionException assertRejectedLoad(MusicBoxSongContainer container, File root) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> container.loadAsync(root).get(3L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RejectedExecutionException,
                "expected a definite rejection, got " + failure.getCause());
        return failure;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shutdownRefusesLoadsWithoutPublishingAndResetRearmsThem() throws Exception {
        File root = tempDir.toFile();
        Files.createDirectory(tempDir.resolve("child"));

        MusicBoxSongContainer.resetLoader();
        try {
            MusicBoxSongContainer before = new MusicBoxSongContainer(root, null);
            before.loadAsync(root).get(3L, TimeUnit.SECONDS);
            assertFalse(before.getSubContainers().isEmpty(), "the open loader must load the sub-folder");

            MusicBoxSongContainer.shutdownLoader();

            MusicBoxSongContainer closed = new MusicBoxSongContainer(root, null);
            assertRejectedLoad(closed, root);
            assertTrue(closed.getSubContainers().isEmpty(), "a refused load must publish nothing");
            assertTrue(closed.getAllSongs().isEmpty());
            assertEquals(0, closed.getAllSongCount());

            MusicBoxSongContainer.resetLoader();

            MusicBoxSongContainer reopened = new MusicBoxSongContainer(root, null);
            reopened.loadAsync(root).get(3L, TimeUnit.SECONDS);
            assertFalse(reopened.getSubContainers().isEmpty(),
                    "resetLoader() must re-arm loading for the next enable, or every reload loads nothing");
        } finally {
            MusicBoxSongContainer.shutdownLoader();
            MusicBoxSongContainer.resetLoader();
        }
    }

    // The synchronous path must fail loudly on an unusable root rather than silently loading an
    // empty library: reload() publishes whatever loadSync() leaves behind.
    @Test
    void syncLoadFailsForAMissingRootInsteadOfPublishingAnEmptyLibrary() throws Exception {
        File missing = new File(tempDir.toFile(), "does-not-exist");
        MusicBoxSongContainer container = new MusicBoxSongContainer(tempDir.toFile(), null);

        assertThrows(RuntimeException.class, () -> container.loadSync(missing));
        assertTrue(container.getAllSongs().isEmpty());
    }
}
