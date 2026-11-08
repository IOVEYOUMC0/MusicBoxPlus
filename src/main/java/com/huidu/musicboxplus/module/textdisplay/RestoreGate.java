package com.huidu.musicboxplus.module.textdisplay;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Session-scoped reservations, retained until completion rather than an arbitrary timeout. */
final class RestoreGate {
    private final Map<String, Token> pending = new HashMap<>();
    private long generation;
    private boolean closed;

    synchronized long generation() {
        return generation;
    }

    synchronized void resetSession() {
        generation++;
        closed = false;
        pending.clear();
    }

    synchronized void closeSession() {
        generation++;
        closed = true;
        pending.clear();
    }

    synchronized Token claim(long session, String name) {
        if (closed || session != generation || pending.containsKey(name)) {
            return null;
        }
        Token token = new Token(session, name);
        pending.put(name, token);
        return token;
    }

    synchronized boolean isCurrent(Token token) {
        return !closed && token.generation == generation && pending.get(token.name) == token;
    }

    synchronized void complete(Token token) {
        // An old callback must never release a newer reservation for the same name.
        pending.remove(token.name, token);
    }

    synchronized void cancel(String name) {
        pending.remove(name);
    }

    void whenReady(Token token, Consumer<Runnable> preparation,
                   Consumer<Runnable> region, Runnable restore) {
        if (!isCurrent(token)) {
            return;
        }
        try {
            // Preparation may finish inline (warm/idle) OR on a worker (cold). In both cases the
            // start callback itself routes to the region; never rely on preparation's dispatcher.
            preparation.accept(() -> {
                if (!isCurrent(token)) {
                    return;
                }
                try {
                    region.accept(() -> restoreIfCurrent(token, restore));
                } catch (RuntimeException | Error failure) {
                    complete(token);
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            complete(token);
            throw failure;
        }
    }

    private synchronized void restoreIfCurrent(Token token, Runnable restore) {
        if (!isCurrent(token)) {
            return;
        }
        // Commit and session reset are mutually exclusive: a reset cannot fall between the
        // generation check and spawning entities. No preparation or scheduling runs under this lock.
        try {
            restore.run();
        } finally {
            complete(token);
        }
    }

    static final class Token {
        private final long generation;
        private final String name;

        private Token(long generation, String name) {
            this.generation = generation;
            this.name = name;
        }
    }
}
