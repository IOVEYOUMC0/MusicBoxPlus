package com.huidu.musicboxplus.module.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WebRateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final Map<String, RequestWindow> clients = new ConcurrentHashMap<>();

    WebRateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    boolean allowRequest(String clientIp) {
        long now = System.currentTimeMillis();
        RequestWindow window = clients.computeIfAbsent(clientIp, k -> new RequestWindow());
        synchronized (window) {
            if (now - window.startTime > windowMs) {
                window.startTime = now;
                window.count = 0;
            }
            if (window.count >= maxRequests) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    void cleanup() {
        long now = System.currentTimeMillis();
        clients.entrySet().removeIf(entry -> now - entry.getValue().startTime > windowMs * 2);
    }

    private static class RequestWindow {
        long startTime = System.currentTimeMillis();
        int count = 0;
    }
}
