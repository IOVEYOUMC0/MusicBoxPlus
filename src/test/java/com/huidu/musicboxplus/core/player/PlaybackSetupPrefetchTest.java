package com.huidu.musicboxplus.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// PlaybackSetup.prefetchNext reads the next entry once.
//
// It used to ask hasNext() and then getNextSongs(1), and for a jukebox each of those answers is a
// full scan of the adjacent container: every slot read, and every item's meta resolved to a song, on
// the region thread, at every track change. Both calls answer the same question, so the second one
// was pure duplication. The counting playlist below is the only way to see that from a test, since
// the real jukebox playlist needs a live inventory.
class PlaybackSetupPrefetchTest {

    @Test
    void readsTheNextEntryOnceWithoutAskingHasNext() {
        CountingPlaylist list = new CountingPlaylist(List.of());

        PlaybackSetup.prefetchNext(list);

        assertEquals(1, list.getNextSongsCalls, "the next entry must be read exactly once");
        assertEquals(0, list.hasNextCalls,
                "hasNext() would scan the jukebox container a second time for the same answer");
    }

    @Test
    void doesNothingWithoutAPlaylist() {
        // Must not throw: callers pass whatever their playlist reference is, including nothing.
        PlaybackSetup.prefetchNext(null);
    }

    @Test
    void anEmptyNextListEndsThePrefetch() {
        CountingPlaylist list = new CountingPlaylist(List.of());

        PlaybackSetup.prefetchNext(list);

        assertFalse(list.nextCalled, "prefetching is a hint; it must not advance the playlist");
    }

    @Test
    void aNullNextEntryIsTolerated() {
        // getNextSongs is allowed to hand back a null slot (a playlist that is still resolving), and
        // prefetching nothing is the right answer there rather than an exception.
        CountingPlaylist list = new CountingPlaylist(java.util.Collections.singletonList(null));

        PlaybackSetup.prefetchNext(list);

        assertEquals(1, list.getNextSongsCalls);
        assertEquals(0, list.hasNextCalls);
    }

    private static final class CountingPlaylist implements IPlayList {

        private final List<MusicBoxSong> next;
        private int getNextSongsCalls;
        private int hasNextCalls;
        private boolean nextCalled;

        CountingPlaylist(List<MusicBoxSong> next) {
            this.next = new ArrayList<>(next);
        }

        @Override
        public void next() {
            nextCalled = true;
        }

        @Override
        public List<? extends MusicBoxSong> getNextSongs(int count) {
            getNextSongsCalls++;
            return next;
        }

        @Override
        public List<? extends MusicBoxSong> getPrevSongs(int count) {
            return List.of();
        }

        @Override
        public boolean hasNext() {
            hasNextCalls++;
            return !next.isEmpty();
        }

        @Override
        public boolean hasPrev() {
            return false;
        }

        @Override
        public MusicBoxSong getCurrent() {
            return null;
        }

        @Override
        public void back(int count) {
        }

        @Override
        public int getSongNum(MusicBoxSong song) {
            return -1;
        }

        @Override
        public void reset() {
        }

        @Override
        public void setSong(MusicBoxSong song) {
        }
    }
}
