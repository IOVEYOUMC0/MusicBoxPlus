package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.core.engine.CompiledSong;

import java.util.LinkedHashMap;
import java.util.Map;

// Keeps the most recently played arrangements alive.
//
// Each MusicBoxSong already holds its own arrangement through a SoftReference, which is the right
// backstop for a library nobody is listening to but the wrong thing to rely on: the collector may
// clear a SoftReference at any allocation, and the cost of getting it back is a full re-read and
// re-parse of the .nbs on whichever thread asked. That thread is the one handling the click that
// started the song, so the symptom is an occasional stall when a song starts -- occasional
// because it depends on whether a collection happened to run in between.
//
// A strong reference to the last N arrangements makes a song that is actually in rotation stay
// compiled. Measured on a 279-song library: about 60 KB per song (17.5 MB for all of them), and
// 1 ms to compile the average song against 16 ms for the largest.
//
// Bounded rather than unbounded because a library can be far larger than the one this was measured
// on, and an arrangement is worth keeping only while it is being played.
public final class CompiledSongCache {

    // Roughly 4 MB at the measured average, which covers the rotation of any normal server.
    private static final int DEFAULT_CAPACITY = 64;

    private static final Map<Key, CompiledSong> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CompiledSong> eldest) {
            return size() > capacity;
        }
    };

    // The resource-pack substitutions an arrangement was built with are part of its identity: the
    // same song compiled under different overrides is a different arrangement.
    private record Key(int songHash, Map<Integer, String> overrides) {
    }

    private static volatile int capacity = DEFAULT_CAPACITY;

    private CompiledSongCache() {
    }

    public static void setCapacity(int newCapacity) {
        capacity = Math.max(0, newCapacity);
        synchronized (CACHE) {
            while (capacity == 0 ? !CACHE.isEmpty() : CACHE.size() > capacity) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
    }

    public static CompiledSong get(int songHash, Map<Integer, String> overrides) {
        synchronized (CACHE) {
            return CACHE.get(new Key(songHash, overrides));
        }
    }

    public static void put(int songHash, Map<Integer, String> overrides, CompiledSong compiled) {
        if (compiled == null || capacity == 0) {
            return;
        }
        synchronized (CACHE) {
            CACHE.put(new Key(songHash, Map.copyOf(overrides)), compiled);
        }
    }

    // Reload rebuilds MusicBoxSong instances from disk, so anything held here describes files that
    // may no longer be what is on disk.
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static int size() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }
}
