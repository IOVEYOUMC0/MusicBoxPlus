package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.MusicBox;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

final class WebAssetHandler implements HttpHandler {
    private static final String ASSET_PREFIX = "web";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_CACHE_SIZE = 50;
    private static final int GZIP_MIN_SIZE = 512;
    private static final Map<String, String> MIME_TYPES = createMimeTypes();

    private final MusicBox plugin;
    private final Consumer<HttpExchange> corsHeaderSetter;
    private final WebRateLimiter rateLimiter;
    private final WebConfig config;
    private final Map<String, byte[]> resourceCache = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );
    // gzip variants of cached resources, keyed by the same path. Built lazily and only for
    // clients that accept gzip, so a client that never asks never pays the compression cost.
    private final Map<String, byte[]> gzipCache = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    WebAssetHandler(MusicBox plugin, Consumer<HttpExchange> corsHeaderSetter,
                    WebRateLimiter rateLimiter, WebConfig config) {
        this.plugin = plugin;
        this.corsHeaderSetter = corsHeaderSetter;
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    // Same proxy-aware client resolution the API routes use, so a client cannot dodge its quota
    // by mixing asset and API requests.

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Static assets get the same per-IP quota as the API routes so a flood cannot hide in
        // the css/js/image paths. The browser's own page load fits comfortably inside it.
        String clientIp = WebApiSupport.clientIp(exchange, config);
        if (!rateLimiter.allowRequest(clientIp)) {
            plugin.getLogger().warning("Rate limit exceeded for web editor client " + clientIp
                    + " on " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            writeTextResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }

        if (path.contains("..") || path.contains("\0")) {
            writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid path");
            return;
        }

        Path basePath = plugin.getDataFolder().toPath().resolve(ASSET_PREFIX).normalize();
        Path targetPath = basePath.resolve(path.substring(1)).normalize();

        if (!targetPath.startsWith(basePath)) {
            writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Access denied");
            return;
        }

        File customFile = targetPath.toFile();
        if (customFile.exists() && customFile.isFile()) {
            serveFile(exchange, customFile, path);
            return;
        }

        byte[] cached = resourceCache.get(path);
        if (cached != null) {
            serveCachedResource(exchange, cached, path);
            return;
        }

        InputStream resource = plugin.getResource(ASSET_PREFIX + path);
        if (resource != null) {
            serveResource(exchange, resource, path);
            return;
        }

        writeTextResponse(exchange, HttpStatus.NOT_FOUND, "Asset not found");
    }

    private void serveFile(HttpExchange exchange, File file, String path) throws IOException {
        String mime = detectMimeType(path);
        // Weak ETag from file identity, so a touched-but-unchanged file still round-trips a 304.
        String etag = weakEtag("f-" + file.lastModified() + "-" + file.length());
        if (sendNotModified(exchange, etag)) {
            return;
        }
        corsHeaderSetter.accept(exchange);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] bytes = readAll(new FileInputStream(file));
        writeBody(exchange, bytes, mime, etag);
    }

    private void serveResource(HttpExchange exchange, InputStream resource, String path) throws IOException {
        byte[] bytes;
        try (InputStream res = resource) {
            bytes = readAll(res);
        }
        resourceCache.put(path, bytes);
        serveCachedResource(exchange, bytes, path);
    }

    private void serveCachedResource(HttpExchange exchange, byte[] bytes, String path) throws IOException {
        String mime = detectMimeType(path);
        String etag = weakEtag("c-" + bytes.length + "-" + hashOf(bytes));
        if (sendNotModified(exchange, etag)) {
            return;
        }
        corsHeaderSetter.accept(exchange);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        writeBody(exchange, bytes, mime, etag);
    }

    // Compresses body bytes when the client accepts gzip and the payload is large enough to be
    // worth it. Compressed copies are cached per path so a burst of requests pays once.
    private void writeBody(HttpExchange exchange, byte[] bytes, String mime, String etag) throws IOException {
        byte[] body = bytes;
        String encoding = null;
        if (bytes.length >= GZIP_MIN_SIZE && acceptsGzip(exchange)) {
            body = gzipCache.computeIfAbsent(etag, ignored -> gzip(bytes));
            if (body.length < bytes.length) {
                encoding = "gzip";
            } else {
                body = bytes;
            }
        }
        if (encoding != null) {
            exchange.getResponseHeaders().set("Content-Encoding", encoding);
        }
        exchange.sendResponseHeaders(HttpStatus.OK, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private boolean sendNotModified(HttpExchange exchange, String etag) throws IOException {
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            corsHeaderSetter.accept(exchange);
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(HttpStatus.NOT_MODIFIED, -1L);
            exchange.close();
            return true;
        }
        return false;
    }

    private boolean acceptsGzip(HttpExchange exchange) {
        String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (acceptEncoding == null) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (trimmed.equals("gzip") || trimmed.startsWith("gzip;")) {
                return true;
            }
        }
        return false;
    }

    private static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(data);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            return data;
        }
    }

    private static String weakEtag(String content) {
        return "W/\"" + content + "\"";
    }

    private static String hashOf(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(bytes.length);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream res = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[BUFFER_SIZE];
            int read;
            while ((read = res.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private void writeTextResponse(HttpExchange exchange, int code, String text) throws IOException {
        corsHeaderSetter.accept(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> createMimeTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(".html", "text/html; charset=UTF-8");
        map.put(".css", "text/css; charset=UTF-8");
        map.put(".js", "application/javascript; charset=UTF-8");
        map.put(".json", "application/json; charset=UTF-8");
        map.put(".png", "image/png");
        map.put(".svg", "image/svg+xml");
        map.put(".ogg", "audio/ogg");
        map.put(".mp3", "audio/mpeg");
        map.put(".wav", "audio/wav");
        map.put(".ttf", "font/ttf");
        map.put(".woff", "font/woff");
        map.put(".woff2", "font/woff2");
        map.put(".ico", "image/x-icon");
        return Collections.unmodifiableMap(map);
    }

    private String detectMimeType(String path) {
        for (Map.Entry<String, String> entry : MIME_TYPES.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "text/plain; charset=UTF-8";
    }
}
