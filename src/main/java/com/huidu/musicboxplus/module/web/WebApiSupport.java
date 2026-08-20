package com.huidu.musicboxplus.module.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// Shared plumbing for every /api handler: request parsing, response writing, CORS, rate
// limiting, session extraction and the pitch/tick/note validation rules. Kept out of
// WebEditorServer so the handlers stay thin and the server class owns lifecycle only.
final class WebApiSupport {
    static final int DEFAULT_WEB_MAX_PITCH = 24;
    static final int BUFFER_SIZE = 8192;
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-fA-F0-9-]{36}$");
    private static final String CORS_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    private static final String CORS_HEADERS = "Content-Type, Authorization";

    private final MusicBox plugin;
    private final WebConfig config;
    private final WebSessionManager sessionManager;
    private final Gson jsonSerializer;
    private final WebRateLimiter rateLimiter;

    WebApiSupport(MusicBox plugin, WebConfig config, WebSessionManager sessionManager,
                  Gson jsonSerializer, WebRateLimiter rateLimiter) {
        this.plugin = plugin;
        this.config = config;
        this.sessionManager = sessionManager;
        this.jsonSerializer = jsonSerializer;
        this.rateLimiter = rateLimiter;
    }

    WebConfig config() {
        return config;
    }

    WebSessionManager sessionManager() {
        return sessionManager;
    }

    Gson json() {
        return jsonSerializer;
    }

    int getMaxRequestSize() {
        return config.getMaxRequestSize() * 1024 * 1024;
    }

    private static final int GZIP_MIN_SIZE = 512;

    void writeJsonResponse(HttpExchange exchange, int code, Object data) throws IOException {
        String json = jsonSerializer.toJson(data);
        setCorsHeaders(exchange);
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        // Song payloads with thousands of notes compress extremely well; only pay for it when
        // the client asks and the payload is big enough to matter.
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        writeBody(exchange, code, bytes);
    }

    private void writeBody(HttpExchange exchange, int code, byte[] bytes) throws IOException {
        byte[] body = bytes;
        if (bytes.length >= GZIP_MIN_SIZE && acceptsGzip(exchange)) {
            byte[] compressed = gzip(bytes);
            if (compressed.length < bytes.length) {
                body = compressed;
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
        }
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private boolean acceptsGzip(HttpExchange exchange) {
        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (acceptEncoding == null) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.equals("gzip") || trimmed.startsWith("gzip;")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] gzip(byte[] data) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(buffer)) {
                gzip.write(data);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return data;
        }
    }

    void writeTextResponse(HttpExchange exchange, int code, String text) throws IOException {
        setCorsHeaders(exchange);
        applySecurityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // Baseline hardening for every API response: the editor is served inside a session URL, so
    // it must not be frameable, its responses must not be sniffed as another type, and the
    // session token in the URL must not leak via a Referer header.
    private void applySecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    void setCorsHeaders(HttpExchange exchange) {
        String allowedOrigin = config.getAllowedOrigin();
        String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");

        if (requestOrigin != null && isOriginAllowed(requestOrigin, allowedOrigin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", requestOrigin);
        } else if ("*".equals(allowedOrigin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
        } else if (requestOrigin == null && allowedOrigin != null && !allowedOrigin.isEmpty()) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
        } else {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "null");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", CORS_METHODS);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", CORS_HEADERS + ", X-CSRF-Token");
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private boolean isOriginAllowed(String requestOrigin, String allowedOrigin) {
        if ("*".equals(allowedOrigin)) {
            return true;
        }
        if (allowedOrigin == null || allowedOrigin.isEmpty()) {
            return isLocalOrigin(requestOrigin);
        }
        String[] allowedOrigins = allowedOrigin.split(",");
        for (String origin : allowedOrigins) {
            String trimmed = origin.trim();
            if (requestOrigin.equalsIgnoreCase(trimmed)) {
                return true;
            }
            if (trimmed.startsWith("*.")) {
                String domain = trimmed.substring(2);
                try {
                    String host = new URI(requestOrigin).getHost();
                    if (host.endsWith(domain) || host.equals(domain)) {
                        return true;
                    }
                } catch (URISyntaxException e) {
                    // A malformed origin simply can't match this wildcard entry
                }
            }
        }
        return false;
    }

    private boolean isLocalOrigin(String requestOrigin) {
        try {
            URI url = new URI(requestOrigin);
            String host = url.getHost();
            int port = url.getPort() == -1 ? getDefaultPort(url.getScheme()) : url.getPort();
            return ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
                && port == config.getPort();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private int getDefaultPort(String scheme) {
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        return 80;
    }

    void handleOptionsRequest(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(HttpStatus.NO_CONTENT, -1L);
        exchange.close();
    }

    Optional<String> extractSessionId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return Optional.empty();

        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2 && "session".equals(parts[0])) {
                String sessionId = parts[1];
                if (SESSION_ID_PATTERN.matcher(sessionId).matches()) {
                    return Optional.of(sessionId);
                }
            }
        }
        return Optional.empty();
    }

    String readRequestBody(HttpExchange exchange, int maxSize) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[BUFFER_SIZE];
            int totalRead = 0;
            int nRead;
            while ((nRead = in.read(data, 0, data.length)) != -1) {
                totalRead += nRead;
                if (totalRead > maxSize) {
                    throw new IOException("Request body too large");
                }
                buffer.write(data, 0, nRead);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    byte[] readRequestBodyBytes(HttpExchange exchange, int maxSize) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[BUFFER_SIZE];
            int totalRead = 0;
            int nRead;
            while ((nRead = in.read(data, 0, data.length)) != -1) {
                totalRead += nRead;
                if (totalRead > maxSize) {
                    throw new IOException("Request body too large");
                }
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        }
    }

    boolean canAccessMusic(WebEditorSession session, PlayerMusic music, HttpExchange exchange) throws IOException {
        if (music == null) {
            return true;
        }
        if (!session.getMusicId().equals(music.getUniqueId().toString())) {
            writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Forbidden");
            return true;
        }
        if (!session.getPlayerId().equals(music.getAuthorUUID().toString())) {
            writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Forbidden");
            return true;
        }
        return false;
    }

    MusicNote parseMusicNoteForWeb(JsonElement element, int maxPitch, int maxTick) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("Note must be an object");
        }
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("pitch") || !obj.has("tick")) {
            throw new JsonParseException("Note must contain pitch and tick");
        }

        int pitch = getJsonInt(obj, "pitch", "Note pitch must be a number");
        int tick = getJsonInt(obj, "tick", "Note tick must be a number");
        if (pitch < 0 || pitch > maxPitch) {
            throw new JsonParseException("Pitch out of range: " + pitch);
        }
        if (tick < 0 || tick >= maxTick) {
            throw new JsonParseException("Tick out of range: " + tick);
        }

        List<MusicNote.NoteInstrument> instruments = new ArrayList<>();
        if (obj.has("instruments") && obj.get("instruments").isJsonArray()) {
            for (JsonElement instElem : obj.getAsJsonArray("instruments")) {
                String name = instElem.isJsonObject()
                        ? readInstrumentId(instElem.getAsJsonObject())
                        : instElem.getAsString();
                MusicNote.NoteInstrument instrument = MusicNote.NoteInstrument.parseExternal(name);
                if (instrument != null && !instruments.contains(instrument)) {
                    instruments.add(instrument);
                }
            }
        }

        if (instruments.isEmpty()) {
            instruments.add(MusicNote.NoteInstrument.HARP);
        }

        return new MusicNote(pitch, tick, instruments);
    }

    String readInstrumentId(JsonObject obj) {
        if (!obj.has("id") || !obj.get("id").isJsonPrimitive()) {
            throw new JsonParseException("Instrument object must contain id");
        }
        return obj.get("id").getAsString();
    }

    int getEditableMaxPitch() {
        if (plugin.getConfigObject() == null || plugin.getConfigObject().getEditor() == null) {
            return DEFAULT_WEB_MAX_PITCH;
        }
        return plugin.getConfigObject().isEnable10octave()
                ? plugin.getConfigObject().getEditor().getExtendedMaxPitch()
                : plugin.getConfigObject().getEditor().getDefaultMaxPitch();
    }

    int getMaxPitchForMusic(PlayerMusic snapshot) {
        int maxPitch = getEditableMaxPitch();
        for (MusicNote note : snapshot.getNotes()) {
            maxPitch = Math.max(maxPitch, note.getPitch());
        }
        return maxPitch;
    }

    int getMaxTickForMusic(PlayerMusic snapshot) {
        return Math.max(config.getMaxTicks(), snapshot.getMaxTick() + 1);
    }

    int getMinBpm() {
        if (plugin.getConfigObject() == null || plugin.getConfigObject().getEditor() == null) {
            return 20;
        }
        return plugin.getConfigObject().getEditor().getMinBpm();
    }

    int getMaxBpm() {
        if (plugin.getConfigObject() == null || plugin.getConfigObject().getEditor() == null) {
            return 300;
        }
        return plugin.getConfigObject().getEditor().getMaxBpm();
    }

    List<String> getSupportedTimeSignatures() {
        List<String> signatures = new ArrayList<>();
        for (PlayerMusic.TimeSignature signature : PlayerMusic.TimeSignature.getAllValues()) {
            signatures.add(signature.toString());
        }
        return signatures;
    }

    int getJsonInt(JsonObject obj, String propertyName, String errorMessage) {
        try {
            return obj.get(propertyName).getAsInt();
        } catch (RuntimeException e) {
            throw new JsonParseException(errorMessage);
        }
    }

    Map<String, Object> buildMusicPayload(PlayerMusic snapshot) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", snapshot.getUniqueId().toString());
        data.put("title", snapshot.getName());
        data.put("tempo", snapshot.getBpm());
        data.put("beatSubdivision", snapshot.getBeatSubdivision());
        data.put("timeSignature", snapshot.getTimeSignature().toString());
        data.put("description", snapshot.getDescription());
        data.put("totalNotes", snapshot.getNoteCount());
        data.put("maxTicks", getMaxTickForMusic(snapshot));
        data.put("maxPitch", getMaxPitchForMusic(snapshot));

        List<Map<String, Object>> notes = new ArrayList<>(snapshot.getNoteCount());
        for (MusicNote note : snapshot.getNotes()) {
            Map<String, Object> noteData = new java.util.HashMap<>();
            noteData.put("pitch", note.getPitch());
            noteData.put("tick", note.getTick());
            noteData.put("instruments", note.getInstruments().stream()
                    .map(MusicNote.NoteInstrument::name)
                    .collect(java.util.stream.Collectors.toList()));
            notes.add(noteData);
        }
        data.put("notes", notes);
        return data;
    }

    String getClientIp(HttpExchange exchange) {
        return clientIp(exchange, config);
    }

    // Static so the asset handler shares it: the rate limiter keys on whatever this returns,
    // so a second copy is where one side stops trusting a forged X-Forwarded-For and the
    // other keeps doing it.
    static String clientIp(HttpExchange exchange, WebConfig config) {
        String remoteAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (config.isTrustedProxy(remoteAddr)) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    boolean checkRateLimit(HttpExchange exchange) throws IOException {
        String clientIp = getClientIp(exchange);
        if (!rateLimiter.allowRequest(clientIp)) {
            plugin.getLogger().warning("Rate limit exceeded for web editor client " + clientIp
                    + " on " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            writeTextResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            return true;
        }
        return false;
    }

    MusicBox plugin() {
        return plugin;
    }
}
