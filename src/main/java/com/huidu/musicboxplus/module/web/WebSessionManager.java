package com.huidu.musicboxplus.module.web;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class WebSessionManager {
    private final ConcurrentHashMap<String, WebEditorSession> activeSessions = new ConcurrentHashMap<>();
    private final WebConfig config;

    WebSessionManager(WebConfig config) {
        this.config = config;
    }

    String create(UUID playerId, UUID musicId) {
        String token = UUID.randomUUID().toString();
        WebEditorSession session = new WebEditorSession(
                playerId.toString(),
                musicId.toString(),
                config.getSessionTimeout()
        );
        activeSessions.put(token, session);
        return token;
    }

    WebEditorSession connect(String token) {
        return activeSessions.computeIfPresent(token, (key, session) -> {
            if (session.isExpired()) {
                return null;
            }
            session.openConnection();
            return session;
        });
    }

    void disconnect(String token) {
        activeSessions.computeIfPresent(token, (key, session) -> {
            session.closeConnection();
            if (session.isExpired()) {
                return null;
            }
            return session;
        });
    }

    void purgeExpiredSessions() {
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    int getSessionCount() {
        return activeSessions.size();
    }
}
