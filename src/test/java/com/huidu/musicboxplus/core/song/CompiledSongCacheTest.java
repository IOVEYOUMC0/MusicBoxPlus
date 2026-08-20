package com.huidu.musicboxplus.core.song;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.nbs.NbsCorpus;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// The point of this cache is to hold arrangements STRONGLY. MusicBoxSong already keeps one behind
// a SoftReference, and a SoftReference is exactly what cannot be relied on: the collector may drop
// it at any allocation, and getting it back costs a full re-read and re-parse of the .nbs on
// whichever thread asked -- the one handling the click that started the song. Measured on the
// bundled corpus that is about 1 ms for an average song and 16 ms for the largest.
class CompiledSongCacheTest {

    private static CompiledSong someSong() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            Path file = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().findFirst().orElseThrow();
            return CompiledSong.compile(NbsReader.read(file));
        }
    }

    @BeforeEach
    void reset() {
        CompiledSongCache.clear();
        CompiledSongCache.setCapacity(64);
    }

    @Test
    void survivesMemoryPressureThatWouldClearASoftReference() throws Exception {
        CompiledSong song = someSong();
        CompiledSongCache.put(1, Map.of(), song);

        // Enough churn that any SoftReference would have been cleared: the JVM is required to
        // clear every one of them before throwing OutOfMemoryError.
        java.lang.ref.SoftReference<byte[]> canary = new java.lang.ref.SoftReference<>(new byte[1024 * 1024]);
        try {
            List<byte[]> ballast = new java.util.ArrayList<>();
            while (canary.get() != null) {
                ballast.add(new byte[8 * 1024 * 1024]);
            }
        } catch (OutOfMemoryError expected) {
            // Reaching the ceiling is the point; everything soft is gone by now.
        }

        assertSame(song, CompiledSongCache.get(1, Map.of()),
                "the cache dropped an arrangement under memory pressure, which is the whole thing "
                        + "it exists to prevent");
    }

    // The same song under different resource-pack substitutions is a different arrangement, so the
    // overrides have to be part of the key rather than something checked afterwards.
    @Test
    void overridesArePartOfTheIdentity() throws Exception {
        CompiledSong plain = someSong();
        CompiledSong overridden = someSong();
        Map<Integer, String> overrides = Map.of(0, "musicboxplus:piano");

        CompiledSongCache.put(7, Map.of(), plain);
        CompiledSongCache.put(7, overrides, overridden);

        assertSame(plain, CompiledSongCache.get(7, Map.of()));
        assertSame(overridden, CompiledSongCache.get(7, overrides));
    }

    @Test
    void evictsTheLeastRecentlyUsedOnceFull() throws Exception {
        CompiledSongCache.setCapacity(2);
        CompiledSong a = someSong();
        CompiledSong b = someSong();
        CompiledSong c = someSong();

        CompiledSongCache.put(1, Map.of(), a);
        CompiledSongCache.put(2, Map.of(), b);
        CompiledSongCache.get(1, Map.of());          // 1 is now the most recently used
        CompiledSongCache.put(3, Map.of(), c);

        assertEquals(2, CompiledSongCache.size());
        assertNotNull(CompiledSongCache.get(1, Map.of()), "the recently played song was evicted");
        assertNotNull(CompiledSongCache.get(3, Map.of()));
        assertNull(CompiledSongCache.get(2, Map.of()), "the least recently used song should have gone");
    }

    @Test
    void capacityZeroDisablesTheCacheAndShrinkingEvictsImmediately() throws Exception {
        CompiledSong song = someSong();
        CompiledSongCache.put(1, Map.of(), song);
        CompiledSongCache.put(2, Map.of(), song);

        CompiledSongCache.setCapacity(1);
        assertEquals(1, CompiledSongCache.size(), "lowering the capacity must evict on the spot");

        CompiledSongCache.setCapacity(0);
        assertEquals(0, CompiledSongCache.size());
        CompiledSongCache.put(3, Map.of(), song);
        assertNull(CompiledSongCache.get(3, Map.of()), "capacity 0 must store nothing");
    }
}
