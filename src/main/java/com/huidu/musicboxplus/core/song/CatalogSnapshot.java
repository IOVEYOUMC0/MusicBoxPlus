package com.huidu.musicboxplus.core.song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Immutable catalog structure with a private, bounded cache belonging only to this snapshot. */
final class CatalogSnapshot<R, S> {
    final R root;
    final List<S> songs;
    final Map<String, S> names;
    final Map<String, S> lowerNames;
    final Map<Integer, S> hashes;
    final Map<String, Set<S>> keywords;
    final int cacheCapacity;
    private final Map<String, List<S>> searchCache;

    CatalogSnapshot(R root, List<S> songs, Map<String, S> names, Map<String, S> lowerNames,
                    Map<Integer, S> hashes, Map<String, Set<S>> keywords, int cacheCapacity) {
        this(root, List.copyOf(songs), Map.copyOf(names), Map.copyOf(lowerNames),
                Map.copyOf(hashes), cacheCapacity, freezeKeywords(keywords));
    }

    // Trusted constructor: callers below reuse already-frozen structural collections in O(1).
    private CatalogSnapshot(R root, List<S> songs, Map<String, S> names, Map<String, S> lowerNames,
                            Map<Integer, S> hashes, int cacheCapacity, Map<String, Set<S>> keywords) {
        this.root = root;
        this.songs = songs;
        this.names = names;
        this.lowerNames = lowerNames;
        this.hashes = hashes;
        this.keywords = keywords;
        this.cacheCapacity = cacheCapacity > 0 ? cacheCapacity : 100;
        final int capacity = this.cacheCapacity;
        this.searchCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<S>> eldest) {
                return size() > capacity;
            }
        };
    }

    private static <S> Map<String, Set<S>> freezeKeywords(Map<String, Set<S>> keywords) {
        Map<String, Set<S>> frozen = new HashMap<>();
        keywords.forEach((key, value) -> frozen.put(key, Collections.unmodifiableSet(new HashSet<>(value))));
        return Collections.unmodifiableMap(frozen);
    }

    CatalogSnapshot<R, S> withFreshCache(int capacity) {
        return new CatalogSnapshot<>(root, songs, names, lowerNames, hashes, capacity, keywords);
    }

    CatalogSnapshot<R, S> withKeywords(Map<String, Set<S>> newKeywords) {
        return new CatalogSnapshot<>(root, songs, names, lowerNames, hashes,
                cacheCapacity, freezeKeywords(newKeywords));
    }

    List<S> search(String key, Function<CatalogSnapshot<R, S>, List<S>> compute) {
        synchronized (searchCache) {
            List<S> cached = searchCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // Never hold a cache/global lock while inspecting songs. A reload or cache clear can publish
        // another snapshot meanwhile; this result can only enter THIS snapshot's cache.
        List<S> result = Collections.unmodifiableList(new ArrayList<>(compute.apply(this)));
        synchronized (searchCache) {
            searchCache.put(key, result);
        }
        return result;
    }

    int cachedSearchCount() {
        synchronized (searchCache) {
            return searchCache.size();
        }
    }

    /** Rebuild outside CAS; never let an alias refresh replace a newer root's catalog. */
    static <R, S> boolean refreshSameRoot(AtomicReference<CatalogSnapshot<R, S>> reference,
                                        UnaryOperator<CatalogSnapshot<R, S>> rebuild) {
        CatalogSnapshot<R, S> previous = reference.get();
        R root = previous.root;
        while (true) {
            CatalogSnapshot<R, S> refreshed = rebuild.apply(previous);
            if (refreshed.root != root) {
                throw new IllegalArgumentException("A catalog refresh must retain its root");
            }
            if (reference.compareAndSet(previous, refreshed)) {
                return true;
            }
            previous = reference.get();
            if (previous.root != root) {
                return false;
            }
        }
    }
}
