package com.huidu.musicboxplus.module.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// GET /api/session: validate a session id and hand the client its CSRF token.
final class SessionApiHandler implements HttpHandler {
    private final WebApiSupport support;

    SessionApiHandler(WebApiSupport support) {
        this.support = support;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            support.handleOptionsRequest(exchange);
            return;
        }

        if (support.checkRateLimit(exchange)) {
            return;
        }

        Optional<String> sessionIdOpt = support.extractSessionId(exchange);
        if (sessionIdOpt.isEmpty()) {
            support.writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Missing or invalid session");
            return;
        }

        String sessionId = sessionIdOpt.get();
        WebEditorSession session = support.sessionManager().connect(sessionId);
        try {
            if (session != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "ok");
                response.put("expiresAt", session.getExpiresAt());
                response.put("csrfToken", session.getCsrfToken());
                support.writeJsonResponse(exchange, HttpStatus.OK, response);
            } else {
                support.writeTextResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid session");
            }
        } finally {
            if (session != null) support.sessionManager().disconnect(sessionId);
        }
    }
}
