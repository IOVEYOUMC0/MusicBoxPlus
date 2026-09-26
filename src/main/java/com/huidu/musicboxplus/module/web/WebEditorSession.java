package com.huidu.musicboxplus.module.web;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class WebEditorSession {
    private final String playerId;
    private final String musicId;
    private final String csrfToken;
    private final int timeoutMinutes;
    private long expiresAt;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private volatile boolean active;
    private volatile long lastActivityAt;

    WebEditorSession(String playerId, String musicId, int timeoutMinutes) {
        this.playerId = playerId;
        this.musicId = musicId;
        this.csrfToken = UUID.randomUUID().toString();
        this.timeoutMinutes = timeoutMinutes;
        this.active = false;
        this.lastActivityAt = Instant.now().getEpochSecond();
        this.expiresAt = Instant.now().plusSeconds(timeoutMinutes * 60L).getEpochSecond();
    }

    void openConnection() {
        activeConnections.incrementAndGet();
        active = true;
        lastActivityAt = Instant.now().getEpochSecond();
    }

    void closeConnection() {
        int remaining = activeConnections.decrementAndGet();
        if (remaining <= 0) {
            activeConnections.set(0);
            active = false;
            lastActivityAt = Instant.now().getEpochSecond();
            expiresAt = Instant.now().plusSeconds(timeoutMinutes * 60L).getEpochSecond();
        }
    }

    boolean isExpired() {
        long now = Instant.now().getEpochSecond();
        if (active && now - lastActivityAt > (long) timeoutMinutes * 60) {
            return true;
        }
        return !active && now > expiresAt;
    }

    boolean validateCsrfToken(String token) {
        return csrfToken != null && csrfToken.equals(token);
    }

    String getPlayerId() {
        return playerId;
    }

    String getMusicId() {
        return musicId;
    }

    String getCsrfToken() {
        return csrfToken;
    }

    long getExpiresAt() {
        return expiresAt;
    }
}
