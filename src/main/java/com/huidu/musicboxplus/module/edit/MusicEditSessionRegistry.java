package com.huidu.musicboxplus.module.edit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Atomically associates a music lock with the exact GUI session that owns it. */
final class MusicEditSessionRegistry<S> {
    private record Lease<S>(UUID owner, S session) {}
    record Admission<S>(boolean admitted, UUID blockingOwner, S previous) {}
    private final Map<UUID, S> sessions = new HashMap<>();
    private final Map<UUID, Lease<S>> locks = new HashMap<>();

    synchronized Admission<S> admit(UUID owner, UUID music, S session) {
        Lease<S> lock = locks.get(music);
        if (lock != null && !lock.owner().equals(owner)) {
            return new Admission<>(false, lock.owner(), null);
        }
        S previous = sessions.put(owner, session);
        if (previous != null) {
            locks.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner)
                    && entry.getValue().session() == previous);
        }
        locks.put(music, new Lease<>(owner, session));
        return new Admission<>(true, null, previous);
    }

    synchronized boolean isCurrent(UUID owner, S expected) {
        return sessions.get(owner) == expected;
    }

    synchronized S get(UUID owner) {
        return sessions.get(owner);
    }

    synchronized Map<UUID, S> snapshot() {
        return new HashMap<>(sessions);
    }

    synchronized boolean release(UUID owner, UUID music, S expected) {
        Lease<S> lock = locks.get(music);
        if (sessions.get(owner) != expected || lock == null
                || !lock.owner().equals(owner) || lock.session() != expected) return false;
        sessions.remove(owner);
        locks.remove(music);
        return true;
    }

    synchronized void removeOfflineLocks(Predicate<UUID> offline) {
        locks.entrySet().removeIf(entry -> offline.test(entry.getValue().owner()));
    }

    synchronized void clear() {
        sessions.clear();
        locks.clear();
    }

    // Compatibility only. New production close paths must use release(owner, music, expected).
    synchronized void legacyReleaseMusic(UUID music) {
        locks.remove(music);
    }

    synchronized void legacySet(UUID owner, S session) {
        sessions.put(owner, session);
    }

    synchronized void legacyRemove(UUID owner) {
        sessions.remove(owner);
    }
}
