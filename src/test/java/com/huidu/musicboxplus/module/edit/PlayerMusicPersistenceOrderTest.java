package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMusicPersistenceOrderTest {
    private static PlayerMusic song(UUID id, String title) {
        return new PlayerMusic(id, title, "author", new UUID(0, 1),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0, List.of(), "");
    }

    @Test
    void snapshotCaptureAndQueuePublicationShareTheModelMonitor() {
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> true);
        AtomicReference<PlayerMusic> modelRef = new AtomicReference<>();
        PlayerMusic guarded = new PlayerMusic(UUID.randomUUID(), "song", "author", new UUID(0, 1),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0, List.of(), "") {
            @Override public synchronized PlayerMusic snapshot() {
                assertTrue(Thread.holdsLock(this));
                PlayerMusic copy = super.snapshot();
                return new PlayerMusic(copy.getUniqueId(), copy.getName(), copy.getAuthor(), copy.getAuthorUUID(),
                        copy.getTimeSignature(), copy.getBpm(), copy.getBeatSubdivision(), 0, 0, List.of(), "") {
                    @Override public synchronized String getName() {
                        assertTrue(Thread.holdsLock(modelRef.get()), "publication fingerprint escaped model lock");
                        return super.getName();
                    }
                };
            }
        };
        modelRef.set(guarded);
        assertNotNull(manager.stageSaveForTest(guarded));
    }

    @Test
    void webCommitKeepsModelMonitorAcrossCaptureWriteAndPublish() {
        AtomicReference<PlayerMusic> liveRef = new AtomicReference<>();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> {
            assertTrue(Thread.holdsLock(liveRef.get()), "editor mutation may race detached commit");
            return true;
        });
        PlayerMusic live = song(UUID.randomUUID(), "original");
        liveRef.set(live);
        manager.stageSaveForTest(live);
        assertTrue(manager.updateMusicSync(live.getUniqueId(), working -> working));
    }

    @Test
    void differentNotesThatCollideUnderTheOldIntFoldMustBothBeWritten() {
        UUID id = UUID.randomUUID(), owner = new UUID(0, 1);
        PlayerMusic first = new PlayerMusic(id, "song", "author", owner,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(45, 0)), "");
        PlayerMusic second = new PlayerMusic(id, "song", "author", owner,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(44, 31)), "");
        assertNotEquals(SongContentFingerprint.of(first),
                SongContentFingerprint.of(second),
                "34*31+45*31 and 34*31+44*31+31 collide as ints; the digest must not");
        List<PlayerMusic> written = new ArrayList<>();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> { written.add(snapshot); return true; });
        assertTrue(manager.persistSaveSync(manager.stageSaveForTest(first)));
        assertTrue(manager.persistSaveSync(manager.stageSaveForTest(second)));
        assertEquals(2, written.size(), "an integer hash match is not proof of identical song content");
        assertEquals(44, written.get(1).getNotes().get(0).getPitch());
        assertEquals(31, written.get(1).getNotes().get(0).getTick());
    }

    @Test
    void queuedOldCompletionCannotOverwriteANewerCommittedSave() {
        List<String> written = new ArrayList<>();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> written.add(snapshot.getName()));
        UUID id = UUID.randomUUID();
        PendingSaves.Save stale = manager.stageSaveForTest(song(id, "old"));
        PendingSaves.Save latest = manager.stageSaveForTest(song(id, "new"));
        assertTrue(manager.persistSaveSync(latest));
        assertTrue(manager.persistSaveSync(stale));
        assertEquals(List.of("new"), written);
        assertNull(manager.pendingSaveForTest(id));
    }

    @Test
    void failedWriteLeavesTheNewerEditPendingInsteadOfRestoringOldContent() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> {
            writing.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("write was not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return false;
        });
        UUID id = UUID.randomUUID();
        PendingSaves.Save old = manager.stageSaveForTest(song(id, "old"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> manager.persistSaveSync(old));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            PendingSaves.Save fresh = manager.stageSaveForTest(song(id, "new"));
            finish.countDown();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            assertSame(fresh, manager.pendingSaveForTest(id));
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedWebUpdateDoesNotPublishOrLeaveARetryToCommitLater() {
        AtomicBoolean allow = new AtomicBoolean(false);
        List<String> written = new ArrayList<>();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> {
            if (!allow.get()) return false;
            written.add(snapshot.getName());
            return true;
        });
        UUID id = UUID.randomUUID();
        PlayerMusic live = song(id, "original");
        manager.stageSaveForTest(live);
        allow.set(true);
        assertTrue(manager.persistSaveSync(manager.pendingSaveForTest(id)));
        allow.set(false);
        assertFalse(manager.updateMusicSync(id, working -> { working.setName("rejected"); return working; }));
        assertEquals("original", live.getName());
        assertNull(manager.pendingSaveForTest(id));
        assertEquals(List.of("original"), written);
    }

    @Test
    void capturedFailedWebRequestCannotBeResurrectedByALateWorker() {
        AtomicReference<PlayerMusicManager> reference = new AtomicReference<>();
        AtomicReference<PendingSaves.Save> captured = new AtomicReference<>();
        UUID id = UUID.randomUUID();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> {
            captured.set(reference.get().pendingSaveForTest(id));
            return false;
        });
        reference.set(manager);
        PlayerMusic live = song(id, "original");
        PendingSaves.Save retry = manager.stageSaveForTest(live);
        assertFalse(manager.updateMusicSync(id, working -> { working.setName("withdrawn"); return working; }));
        assertFalse(manager.persistSaveSync(captured.get()));
        assertSame(retry, manager.pendingSaveForTest(id));
    }

    @Test
    void failedWebUpdatePreservesAnExistingEditorRetry() {
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> false);
        UUID id = UUID.randomUUID();
        PlayerMusic live = song(id, "editor retry");
        PendingSaves.Save before = manager.stageSaveForTest(live);
        assertFalse(manager.updateMusicSync(id, working -> { working.setName("rejected"); return working; }));
        assertSame(before, manager.pendingSaveForTest(id));
        assertEquals("editor retry", live.getName());
    }

    @Test
    void liveContentIsPublishedOnlyAfterTheDetachedWriteSucceeds() {
        AtomicReference<PlayerMusic> liveRef = new AtomicReference<>();
        PlayerMusicManager manager = new PlayerMusicManager(snapshot -> {
            assertEquals("original", liveRef.get().getName(), "uncommitted content leaked to readers");
            assertEquals("committed", snapshot.getName());
            return true;
        });
        UUID id = UUID.randomUUID();
        PlayerMusic live = song(id, "original");
        liveRef.set(live);
        manager.stageSaveForTest(live);
        assertTrue(manager.updateMusicSync(id, working -> { working.setName("committed"); return working; }));
        assertEquals("committed", live.getName());
        assertNull(manager.pendingSaveForTest(id));
    }
}
