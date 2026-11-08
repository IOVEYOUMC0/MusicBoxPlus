package com.huidu.musicboxplus.module.web;

import com.huidu.musicboxplus.MusicBox;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
    private final AssetStore assets;

    enum AssetSource { BUILTIN, CUSTOM }

    record AssetKey(AssetSource source, String path, String etag) { }

    record CachedAsset(byte[] bytes, AssetKey key) {
        String etag() {
            return key.etag();
        }
    }

    @FunctionalInterface
    interface ResourceLoader {
        InputStream open(String path) throws IOException;
    }

    // Pure filesystem/resource cache: production and tests use the same lookup and gzip paths,
    // without needing to construct a Bukkit plugin. Only immutable bundled assets are read-cached.
    static final class AssetStore {
        private final Path webRoot;
        private final ResourceLoader resources;
        private final Map<String, CachedAsset> resourceCache = boundedCache();
        private final Map<AssetKey, byte[]> gzipCache = boundedCache();

        AssetStore(Path webRoot, ResourceLoader resources) {
            this.webRoot = webRoot;
            this.resources = resources;
        }

        CachedAsset load(String path) throws IOException {
            Path custom = resolveCustomFile(webRoot, path);
            if (custom != null) {
                // Read before evaluating validators: mtime and length alone cannot detect edits.
                return asset(AssetSource.CUSTOM, path, Files.readAllBytes(custom));
            }
            CachedAsset cached = resourceCache.get(path);
            if (cached != null) {
                return cached;
            }
            InputStream resource = resources.open(ASSET_PREFIX + path);
            if (resource == null) {
                return null;
            }
            CachedAsset loaded = asset(AssetSource.BUILTIN, path, readAll(resource));
            resourceCache.put(path, loaded);
            return loaded;
        }

        byte[] compressed(CachedAsset asset) {
            return gzipCache.computeIfAbsent(asset.key(), ignored -> gzip(asset.bytes()));
        }

        private static CachedAsset asset(AssetSource source, String path, byte[] bytes) {
            String etag = weakEtag(source.name() + "-" + hashOf(bytes));
            return new CachedAsset(bytes, new AssetKey(source, path, etag));
        }
    }

    private static <K, V> Map<K, V> boundedCache() {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    static Path resolveCustomFile(Path webRoot, String requestPath) throws IOException {
        if (requestPath == null || !requestPath.startsWith("/") || requestPath.startsWith("//")
                || requestPath.contains("..") || requestPath.contains("\\") || requestPath.contains("\0")) {
            throw new IllegalArgumentException("Invalid asset path");
        }
        Path base = webRoot.toAbsolutePath().normalize();
        Path target = base.resolve(requestPath.substring(1)).normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("Asset path outside web root");
        }
        Path realBase;
        try {
            realBase = base.toRealPath();
        } catch (NoSuchFileException absent) {
            return null;
        }
        // Check the nearest existing ancestor too: a missing file under an escaping directory
        // symlink/junction must not silently fall back to a bundled resource.
        Path existing = target;
        while (existing != null) {
            try {
                Path realTarget = existing.toRealPath();
                if (!realTarget.startsWith(realBase)) {
                    throw new SecurityException("Asset real path outside web root");
                }
                return existing.equals(target) && Files.isRegularFile(realTarget) ? realTarget : null;
            } catch (NoSuchFileException absent) {
                existing = existing.getParent();
            }
        }
        return null;
    }

    WebAssetHandler(MusicBox plugin, Consumer<HttpExchange> corsHeaderSetter,
                    WebRateLimiter rateLimiter, WebConfig config) {
        this.plugin = plugin;
        this.corsHeaderSetter = corsHeaderSetter;
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.assets = new AssetStore(plugin.getDataFolder().toPath().resolve(ASSET_PREFIX), plugin::getResource);
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

        CachedAsset asset;
        try {
            asset = assets.load(path);
        } catch (IllegalArgumentException invalid) {
            writeTextResponse(exchange, HttpStatus.BAD_REQUEST, "Invalid path");
            return;
        } catch (SecurityException forbidden) {
            writeTextResponse(exchange, HttpStatus.FORBIDDEN, "Access denied");
            return;
        }
        if (asset == null) {
            writeTextResponse(exchange, HttpStatus.NOT_FOUND, "Asset not found");
            return;
        }
        serveAsset(exchange, asset, path, assets, corsHeaderSetter);
    }

    static void serveAsset(HttpExchange exchange, CachedAsset asset, String path, AssetStore assets,
                           Consumer<HttpExchange> corsHeaderSetter) throws IOException {
        String mime = detectMimeType(path);
        String etag = asset.etag();
        corsHeaderSetter.accept(exchange);
        exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
        exchange.getResponseHeaders().set("Cache-Control", asset.key().source() == AssetSource.CUSTOM
                ? "public, max-age=3600" : "public, max-age=86400");
        exchange.getResponseHeaders().set("ETag", etag);
        if (sendNotModified(exchange, etag)) {
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", mime);
        writeBody(exchange, asset, assets);
    }

    // Variant keys include source, request path and a digest of the bytes actually being served.
    private static void writeBody(HttpExchange exchange, CachedAsset asset, AssetStore assets) throws IOException {
        byte[] bytes = asset.bytes();
        byte[] body = bytes;
        String encoding = null;
        if (bytes.length >= GZIP_MIN_SIZE && WebApiSupport.acceptsGzip(exchange)) {
            body = assets.compressed(asset);
            if (body.length < bytes.length) {
                encoding = "gzip";
            } else {
                body = bytes;
            }
        }
        if (encoding != null) {
            exchange.getResponseHeaders().set("Content-Encoding", encoding);
        }
        WebApiSupport.applySecurityHeaders(exchange);
        exchange.sendResponseHeaders(HttpStatus.OK, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static boolean sendNotModified(HttpExchange exchange, String etag) throws IOException {
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            WebApiSupport.applySecurityHeaders(exchange);
            exchange.sendResponseHeaders(HttpStatus.NOT_MODIFIED, -1L);
            exchange.close();
            return true;
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
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
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        WebApiSupport.applySecurityHeaders(exchange);
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

    private static String detectMimeType(String path) {
        for (Map.Entry<String, String> entry : MIME_TYPES.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "text/plain; charset=UTF-8";
    }
}
