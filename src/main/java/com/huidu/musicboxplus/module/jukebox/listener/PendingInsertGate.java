package com.huidu.musicboxplus.module.jukebox.listener;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Identity leases for hopper inserts; independent of Bukkit so the production gate is testable. */
final class PendingInsertGate<K> {
    private final Map<K, Token<K>> pending = new HashMap<>();
    private final long timeoutNanos;
    private final LongSupplier clock;

    PendingInsertGate(long timeoutNanos, LongSupplier clock) {
        this.timeoutNanos = timeoutNanos;
        this.clock = clock;
    }

    synchronized Token<K> acquire(K key) {
        long now = clock.getAsLong();
        Token<K> current = pending.get(key);
        if (current != null && now - current.startedAt <= timeoutNanos) {
            return null;
        }
        Token<K> token = new Token<>(key, now);
        pending.put(key, token);
        return token;
    }

    synchronized boolean isCurrent(Token<K> token) {
        return token != null && pending.get(token.key) == token
                && clock.getAsLong() - token.startedAt <= timeoutNanos;
    }

    /**
     * Checking and mutating are one operation: expiry takeover/reset cannot interleave between a
     * successful check and removal from the source or insertion into the destination. Tasks must
     * dispatch to their owning region BEFORE entering this method, never wait for another region
     * while holding the gate.
     */
    synchronized boolean runIfCurrent(Token<K> token, Runnable mutation) {
        if (!isCurrent(token)) {
            return false;
        }
        mutation.run();
        return true;
    }

    synchronized void release(Token<K> token) {
        if (token != null) {
            pending.remove(token.key, token);
        }
    }

    synchronized void reset() {
        pending.clear();
    }

    // Deliberately no value equality: even leases acquired at the same nanoTime are distinct.
    static final class Token<K> {
        private final K key;
        private final long startedAt;

        private Token(K key, long startedAt) {
            this.key = key;
            this.startedAt = startedAt;
        }
    }
}
