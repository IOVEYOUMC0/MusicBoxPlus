package com.huidu.musicboxplus.module.edit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Latest detached snapshot awaiting persistence for each song. */
final class PendingSaves {
    record Save(PlayerMusic snapshot, long version, SongContentFingerprint fingerprint) { }

    private final Map<UUID, Save> pending = new ConcurrentHashMap<>();
    // Never reset while callbacks referencing this queue can still arrive (ABA protection).
    private final AtomicLong sequence = new AtomicLong();

    Save stage(UUID musicId, PlayerMusic snapshot) {
        SongContentFingerprint fingerprint = SongContentFingerprint.of(snapshot);
        return pending.compute(musicId, (id, previous) ->
                new Save(snapshot, sequence.incrementAndGet(), fingerprint));
    }

    Save peek(UUID musicId) { return pending.get(musicId); }
    Collection<Save> staged() { return List.copyOf(pending.values()); }
    boolean isEmpty() { return pending.isEmpty(); }
    int size() { return pending.size(); }

    /** Only the completion that owns this exact token may retire the entry. */
    boolean retire(UUID musicId, long version) {
        Save current = pending.get(musicId);
        return current != null && current.version() == version && pending.remove(musicId, current);
    }

    /** Failure leaves the entry queued, but never reinserts a token superseded by an edit/delete. */
    boolean restoreAfterFailure(UUID musicId, Save failed) {
        return pending.get(musicId) == failed;
    }

    void restoreIfEmpty(UUID musicId, Save previous) { pending.putIfAbsent(musicId, previous); }
    void drop(UUID musicId) { pending.remove(musicId); }
    void clear() { pending.clear(); }
}
