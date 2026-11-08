package com.huidu.musicboxplus.module.edit;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// PendingSaves is the queue that decides which snapshot reaches the database next. The case that
// matters is a write that fails after the player has already edited again: the failed snapshot must
// not be queued back over the newer one, which is what the old manager did unconditionally.
class PendingSavesTest {

    private final PendingSaves queue = new PendingSaves();

    private static PlayerMusic music() {
        return new PlayerMusic(UUID.randomUUID(), "song", "author", UUID.randomUUID(),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0L, 0L, List.of(), "");
    }

    @Test
    void stagedSnapshotBecomesTheNewestPendingSave() {
        UUID musicId = UUID.randomUUID();
        PlayerMusic first = music();
        PlayerMusic second = music();

        PendingSaves.Save firstSave = queue.stage(musicId, first);
        PendingSaves.Save secondSave = queue.stage(musicId, second);

        assertSame(second, queue.peek(musicId).snapshot());
        assertTrue(secondSave.version() > firstSave.version());
        assertEquals(secondSave.fingerprint(), queue.peek(musicId).fingerprint());
        assertEquals(1, queue.size());
    }

    @Test
    void failedWriteCannotQueueItsSnapshotOverANewerOne() {
        UUID musicId = UUID.randomUUID();
        PlayerMusic older = music();
        PlayerMusic newer = music();

        PendingSaves.Save inFlight = queue.stage(musicId, older);
        queue.stage(musicId, newer); // player edited again while the write was running

        boolean restored = queue.restoreAfterFailure(musicId, inFlight);

        assertFalse(restored, "the newer snapshot must win");
        assertSame(newer, queue.peek(musicId).snapshot());
    }

    @Test
    void failedWriteIsRetriedWhenNothingNewerArrived() {
        UUID musicId = UUID.randomUUID();
        PlayerMusic snapshot = music();
        PendingSaves.Save save = queue.stage(musicId, snapshot);

        // A successful attempt retires the entry; the failing one must keep it in place.
        assertTrue(queue.retire(musicId, save.version()));
        assertNull(queue.peek(musicId));

        PendingSaves.Save retried = queue.stage(musicId, snapshot);
        assertTrue(queue.restoreAfterFailure(musicId, retried));
        assertSame(snapshot, queue.peek(musicId).snapshot());
    }

    @Test
    void retiringAStaleSaveLeavesTheNewerOneAlone() {
        UUID musicId = UUID.randomUUID();
        PendingSaves.Save stale = queue.stage(musicId, music());
        PlayerMusic newer = music();
        queue.stage(musicId, newer);

        assertFalse(queue.retire(musicId, stale.version()));
        assertNotNull(queue.peek(musicId));
        assertSame(newer, queue.peek(musicId).snapshot());
    }

    @Test
    void retireAndDropRemoveOnlyTheAddressedSong() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PendingSaves.Save firstSave = queue.stage(first, music());
        queue.stage(second, music());

        assertTrue(queue.retire(first, firstSave.version()));
        assertNull(queue.peek(first));
        assertNotNull(queue.peek(second));

        queue.drop(second);
        assertNull(queue.peek(second));
        assertTrue(queue.isEmpty());
    }

    // A retired token must not be reusable after the queue drains: the old per-song counter restarted
    // at 1, so a late completion could retire or restore over a completely different later save.
    @Test
    void retiredVersionCannotRetireANewSaveAfterQueueDrains() {
        UUID id = UUID.randomUUID();
        PendingSaves.Save old = queue.stage(id, music());
        assertTrue(queue.retire(id, old.version()));
        PendingSaves.Save fresh = queue.stage(id, music());

        assertTrue(fresh.version() > old.version());
        assertFalse(queue.retire(id, old.version()));
        assertSame(fresh, queue.peek(id));
    }

    @Test
    void staleFailureCannotRestoreContentAfterDropOrClear() {
        UUID id = UUID.randomUUID();
        PendingSaves.Save old = queue.stage(id, music());
        queue.drop(id);
        PendingSaves.Save afterDrop = queue.stage(id, music());
        assertFalse(queue.restoreAfterFailure(id, old));
        assertSame(afterDrop, queue.peek(id));

        queue.clear();
        PendingSaves.Save afterClear = queue.stage(id, music());
        assertFalse(queue.restoreAfterFailure(id, afterDrop));
        assertSame(afterClear, queue.peek(id));
    }

    @Test
    void clearDropsEveryStagedSave() {
        queue.stage(UUID.randomUUID(), music());
        queue.stage(UUID.randomUUID(), music());

        queue.clear();

        assertTrue(queue.isEmpty());
        assertTrue(queue.staged().isEmpty());
    }
}
