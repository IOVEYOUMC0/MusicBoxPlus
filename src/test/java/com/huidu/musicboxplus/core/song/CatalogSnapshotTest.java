package com.huidu.musicboxplus.core.song;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact production catalog/cache helper without loading Bukkit or the manager. */
class CatalogSnapshotTest {
    private static CatalogSnapshot<Object, String> catalog(Object root, String song, int capacity) {
        return new CatalogSnapshot<>(root, List.of(song), Map.of(song, song), Map.of(song, song),
                Map.of(song.hashCode(), song), Map.of(song, Set.of(song)), capacity);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "worker did not reach/release its barrier");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void stop(ExecutorService executor, CountDownLatch release) throws InterruptedException {
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void oldSearchFinishingAfterReloadCannotWriteIntoNewCatalogCache() throws Exception {
        var oldCatalog = catalog(new Object(), "old", 2);
        var newCatalog = catalog(new Object(), "new", 2);
        var current = new AtomicReference<>(oldCatalog);
        var computing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldSearch = executor.submit(() -> {
                var captured = current.get();
                return captured.search("query", snapshot -> {
                    computing.countDown();
                    await(release);
                    return snapshot.songs;
                });
            });
            await(computing);
            assertEquals(List.of("independent"), oldCatalog.search("other query", ignored -> List.of("independent")),
                    "a slow search must not hold the cache lock while computing");
            current.set(newCatalog);
            assertEquals(List.of("new"), newCatalog.search("query", snapshot -> snapshot.songs));
            release.countDown();
            assertEquals(List.of("old"), oldSearch.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("new"), current.get().search("query", ignored -> fail("new cache was overwritten")));
            assertEquals(2, oldCatalog.cachedSearchCount());
            assertEquals(1, newCatalog.cachedSearchCount());
        } finally {
            stop(executor, release);
        }
    }

    @Test
    void cacheClearCreatesAnIsolatedCacheEvenForTheSameRootAndQuery() throws Exception {
        var original = catalog(new Object(), "song", 2);
        var current = new AtomicReference<>(original);
        var computing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldSearch = executor.submit(() -> original.search("query", snapshot -> {
                computing.countDown();
                await(release);
                return List.of("stale metadata result");
            }));
            await(computing);
            current.updateAndGet(snapshot -> snapshot.withFreshCache(snapshot.cacheCapacity));
            var cleared = current.get();
            assertSame(original.root, cleared.root);
            assertEquals(0, cleared.cachedSearchCount());
            assertEquals(List.of("fresh"), cleared.search("query", ignored -> List.of("fresh")));
            release.countDown();
            assertEquals(List.of("stale metadata result"), oldSearch.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("fresh"), cleared.search("query", ignored -> fail("fresh cache lost")));
        } finally {
            stop(executor, release);
        }
    }

    @Test
    void capturedRootListAndEveryIndexRemainOneGenerationWhileNewSnapshotIsPublished() throws Exception {
        var oldCatalog = catalog(new Object(), "old", 2);
        var newCatalog = catalog(new Object(), "new", 2);
        var current = new AtomicReference<>(oldCatalog);
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var reader = executor.submit(() -> {
                var snapshot = current.get();
                captured.countDown();
                await(release);
                assertCoherent(snapshot, oldCatalog.root, "old");
            });
            await(captured);
            current.set(newCatalog);
            assertCoherent(current.get(), newCatalog.root, "new");
            release.countDown();
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            stop(executor, release);
        }
    }

    private static void assertCoherent(CatalogSnapshot<Object, String> snapshot, Object root, String song) {
        assertSame(root, snapshot.root);
        assertEquals(List.of(song), snapshot.songs);
        assertEquals(Map.of(song, song), snapshot.names);
        assertEquals(Map.of(song, song), snapshot.lowerNames);
        assertEquals(Map.of(song.hashCode(), song), snapshot.hashes);
        assertEquals(Map.of(song, Set.of(song)), snapshot.keywords);
    }

    @Test
    void constructorFreezesAllIndexesIncludingNestedKeywordSets() {
        var songs = new ArrayList<>(List.of("song"));
        var names = new HashMap<>(Map.of("song", "song"));
        var lowerNames = new HashMap<>(names);
        var hashes = new HashMap<>(Map.of(1, "song"));
        var matches = new HashSet<>(Set.of("song"));
        Map<String, Set<String>> keywords = new HashMap<>(Map.of("keyword", matches));
        var snapshot = new CatalogSnapshot<>(new Object(), songs, names, lowerNames, hashes, keywords, 2);
        songs.clear();
        names.clear();
        lowerNames.clear();
        hashes.clear();
        matches.clear();
        keywords.clear();
        assertEquals(List.of("song"), snapshot.songs);
        assertEquals(Map.of("song", "song"), snapshot.names);
        assertEquals(Map.of("song", "song"), snapshot.lowerNames);
        assertEquals(Map.of(1, "song"), snapshot.hashes);
        assertEquals(Set.of("song"), snapshot.keywords.get("keyword"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.songs.add("other"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.names.put("other", "other"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lowerNames.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.hashes.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.keywords.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.keywords.get("keyword").clear());
        List<String> result = snapshot.search("query", ignored -> List.of("song"));
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test
    void lruRemainsBoundedAndAccessOrderedAcrossMissesAndSnapshotRefreshes() {
        var snapshot = catalog(new Object(), "song", 2);
        assertLru(snapshot);
        var cleared = snapshot.withFreshCache(snapshot.cacheCapacity);
        assertSame(snapshot.songs, cleared.songs);
        assertSame(snapshot.keywords, cleared.keywords);
        assertEquals(2, cleared.cacheCapacity);
        assertLru(cleared);
        var refreshed = cleared.withKeywords(Map.of("alias", Set.of("song")));
        assertEquals(2, refreshed.cacheCapacity);
        assertEquals(0, refreshed.cachedSearchCount());
        assertLru(refreshed);
        var resized = refreshed.withFreshCache(1);
        resized.search("a", ignored -> List.of("a"));
        resized.search("b", ignored -> List.of("b"));
        assertEquals(1, resized.cachedSearchCount());
        assertEquals(1, resized.withKeywords(Map.of()).cacheCapacity);
    }

    private static void assertLru(CatalogSnapshot<Object, String> snapshot) {
        snapshot.search("a", ignored -> List.of("a"));
        snapshot.search("b", ignored -> List.of("b"));
        assertEquals(List.of("a"), snapshot.search("a", ignored -> fail("a must be cached")));
        snapshot.search("c", ignored -> List.of("c"));
        assertEquals(2, snapshot.cachedSearchCount());
        assertEquals(List.of("a"), snapshot.search("a", ignored -> fail("recent entry evicted")));
        assertEquals(List.of("c"), snapshot.search("c", ignored -> fail("new entry evicted")));
        var recomputations = new AtomicInteger();
        snapshot.search("b", ignored -> {
            recomputations.incrementAndGet();
            return List.of("b");
        });
        assertEquals(1, recomputations.get(), "least recently used entry must have been evicted");
        assertEquals(2, snapshot.cachedSearchCount());
        for (int i = 0; i < 50; i++) {
            snapshot.search("miss-" + i, ignored -> List.of("result"));
            assertEquals(2, snapshot.cachedSearchCount(), "misses must never replace the bounded LRU");
        }
    }

    @Test
    void aliasRefreshCasCannotRollBackAReloadPublishedWhileIndexWasBuilding() throws Exception {
        var oldCatalog = catalog(new Object(), "old", 2);
        var newCatalog = catalog(new Object(), "new", 3);
        var current = new AtomicReference<>(oldCatalog);
        var rebuilding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var refresh = executor.submit(() -> CatalogSnapshot.refreshSameRoot(current, snapshot -> {
                rebuilding.countDown();
                await(release);
                return snapshot.withKeywords(Map.of("alias", Set.of("old")));
            }));
            await(rebuilding);
            current.set(newCatalog);
            release.countDown();
            assertFalse(refresh.get(5, TimeUnit.SECONDS));
            assertSame(newCatalog, current.get());
            assertCoherent(current.get(), newCatalog.root, "new");
        } finally {
            stop(executor, release);
        }
    }

    @Test
    void aliasRefreshRetriesSameRootCasAndRetainsConcurrentCacheReconfiguration() throws Exception {
        var initial = catalog(new Object(), "song", 2);
        var current = new AtomicReference<>(initial);
        var rebuilding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var builds = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var refresh = executor.submit(() -> CatalogSnapshot.refreshSameRoot(current, snapshot -> {
                if (builds.incrementAndGet() == 1) {
                    rebuilding.countDown();
                    await(release);
                }
                return snapshot.withKeywords(Map.of("alias", Set.of("song")));
            }));
            await(rebuilding);
            current.updateAndGet(snapshot -> snapshot.withFreshCache(7));
            release.countDown();
            assertTrue(refresh.get(5, TimeUnit.SECONDS));
            assertEquals(2, builds.get());
            assertSame(initial.root, current.get().root);
            assertEquals(7, current.get().cacheCapacity);
            assertEquals(Set.of("song"), current.get().keywords.get("alias"));
        } finally {
            stop(executor, release);
        }
    }
}
