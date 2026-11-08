package com.huidu.musicboxplus.module.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class WebAssetHandlerTest {
    @TempDir Path temporary;

    @Test
    void gzipRefreshesAcrossTwoSamePathEditsEvenWithUnchangedMtimeAndLength() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Path file = web.resolve("app.js");
        FileTime time = FileTime.fromMillis(1_700_000_000_000L);
        WebAssetHandler.AssetStore store = new WebAssetHandler.AssetStore(web, ignored -> null);
        String previousEtag = null;
        for (char value : new char[]{'a', 'b', 'c'}) {
            byte[] expected = body(value);
            Files.write(file, expected);
            Files.setLastModifiedTime(file, time);
            WebAssetHandler.CachedAsset asset = store.load("/app.js");
            assertNotEquals(previousEtag, asset.etag());
            Exchange exchange = get("gzip");
            if (previousEtag != null) exchange.request.set("If-None-Match", previousEtag);
            serve(store, asset, "/app.js", exchange);
            assertGzipBody(expected, exchange);
            assertSame(store.compressed(asset), store.compressed(store.load("/app.js")),
                    "unchanged bytes should reuse their compressed variant");
            previousEtag = asset.etag();
        }
    }

    @Test
    void builtinCustomAndBuiltinAgainNeverShareStaleVariants() throws Exception {
        Path web = temporary.resolve("web"); // Missing custom web directory must allow bundled assets.
        AtomicInteger resourceReads = new AtomicInteger();
        byte[] builtin = body('i');
        WebAssetHandler.AssetStore store = new WebAssetHandler.AssetStore(web, path -> {
            assertEquals("web/app.js", path);
            resourceReads.incrementAndGet();
            return new ByteArrayInputStream(builtin);
        });
        WebAssetHandler.CachedAsset first = store.load("/app.js");
        Exchange initial = get("gzip");
        serve(store, first, "/app.js", initial);
        assertGzipBody(builtin, initial);

        Files.createDirectory(web);
        Path override = web.resolve("app.js");
        Files.write(override, body('o'));
        WebAssetHandler.CachedAsset custom = store.load("/app.js");
        assertNotEquals(first.key(), custom.key());
        assertEquals(WebAssetHandler.AssetSource.CUSTOM, custom.key().source());
        Exchange overridden = get("gzip");
        overridden.request.set("If-None-Match", first.etag());
        serve(store, custom, "/app.js", overridden);
        assertGzipBody(body('o'), overridden);

        // Source identity remains distinct even when both sources contain identical bytes.
        Files.write(override, builtin);
        assertNotEquals(first.key(), store.load("/app.js").key());
        Files.delete(override);
        WebAssetHandler.CachedAsset restored = store.load("/app.js");
        assertSame(first, restored);
        Exchange fallback = get("gzip");
        serve(store, restored, "/app.js", fallback);
        assertGzipBody(builtin, fallback);
        assertEquals(1, resourceReads.get());
    }

    @Test
    void equalMtimeAndLengthOnDifferentPathsCannotMixCompressedBodies() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        FileTime sameTime = FileTime.fromMillis(1_700_000_000_000L);
        WebAssetHandler.AssetStore store = new WebAssetHandler.AssetStore(web, ignored -> null);
        for (String name : new String[]{"first.js", "second.js"}) {
            Path file = web.resolve(name);
            byte[] expected = body(name.charAt(0));
            Files.write(file, expected);
            Files.setLastModifiedTime(file, sameTime);
            Exchange exchange = get("gzip");
            WebAssetHandler.CachedAsset asset = store.load("/" + name);
            assertEquals("/" + name, asset.key().path());
            serve(store, asset, "/" + name, exchange);
            assertGzipBody(expected, exchange);
        }
        Files.write(web.resolve("second.js"), body('f'));
        assertNotEquals(store.load("/first.js").key(), store.load("/second.js").key(),
                "paths remain part of the key even for identical content");
    }

    @Test
    void staticResponsesHonorZeroQualityCaseWhitespaceAndMultipleHeaderLines() throws Exception {
        WebAssetHandler.AssetStore store = bundledStore();
        WebAssetHandler.CachedAsset asset = store.load("/app.js");
        for (String header : new String[]{"gzip;q=0", "gzip;q=0.0", " GZip ; Q = 0.000 ",
                "*;q=1, GZIP ; q = 0", "gzip;q=0, gzip;q=1"}) {
            Exchange exchange = get(header);
            serve(store, asset, "/app.js", exchange);
            assertEquals(200, exchange.status, header);
            assertNull(exchange.response.getFirst("Content-Encoding"), header);
            assertArrayEquals(body('x'), exchange.output.toByteArray(), header);
            assertVary(exchange);
        }
        Exchange accepted = get("br;q=1");
        accepted.request.add("Accept-Encoding", " GZip ; Q = 0.5 ");
        serve(store, asset, "/app.js", accepted);
        assertGzipBody(body('x'), accepted);
    }

    @Test
    void notModifiedKeepsEncodingAndOriginVaryAndHasNoBody() throws Exception {
        WebAssetHandler.AssetStore store = bundledStore();
        WebAssetHandler.CachedAsset asset = store.load("/app.js");
        Exchange exchange = get("gzip;q=0.0");
        exchange.request.set("If-None-Match", asset.etag());
        serve(store, asset, "/app.js", exchange);
        assertEquals(304, exchange.status);
        assertEquals(-1, exchange.length);
        assertEquals(0, exchange.output.size());
        assertEquals(asset.etag(), exchange.response.getFirst("ETag"));
        assertEquals("nosniff", exchange.response.getFirst("X-Content-Type-Options"));
        assertVary(exchange);
        assertTrue(exchange.closed);
    }

    @Test
    void apiResponsesUseTheSameGzipNegotiationWithoutStartingBukkit() throws Exception {
        byte[] json = ("\"" + "x".repeat(4096) + "\"").getBytes(StandardCharsets.UTF_8);
        for (String header : new String[]{" GZip ; Q = 0.0 ", "gzip ; q=0.000, *;q=1"}) {
            Exchange exchange = get(header);
            exchange.response.set("Vary", "Origin");
            WebApiSupport.writeBody(exchange, 200, json);
            assertNull(exchange.response.getFirst("Content-Encoding"));
            assertEquals("\"" + "x".repeat(4096) + "\"", exchange.output.toString(StandardCharsets.UTF_8));
            assertVary(exchange);
        }
        Exchange compressed = get(" GZip ; Q = 1.0 ");
        compressed.response.set("Vary", "Origin");
        WebApiSupport.writeBody(compressed, 200, json);
        assertGzipBody(("\"" + "x".repeat(4096) + "\"").getBytes(StandardCharsets.UTF_8), compressed);
    }

    @Test
    void sharedParserRejectsInvalidQualityAndHonorsExplicitWildcardOverride() {
        assertFalse(WebApiSupport.acceptsGzip((String) null));
        for (String header : new String[]{"", "br", "gzip;q=NaN", "gzip;q=-1", "gzip;q=1.1",
                "gzip;q=", "gzip;q=abc", "gzip;q=0.5;q=1", "gzip;q=0.0,*;q=1"}) {
            assertFalse(WebApiSupport.acceptsGzip(header), header);
        }
        for (String header : new String[]{"gzip", " GZIP ; Q = 0.001 ", "*;q=0.5", "gzip;q=1,*;q=0"}) {
            assertTrue(WebApiSupport.acceptsGzip(header), header);
        }
    }

    @Test
    void normalNestedMissingAndDirectoryPathsAreHandledWithinRoot() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Path directory = Files.createDirectory(web.resolve("assets"));
        Path file = Files.write(directory.resolve("app.js"), body('n'));
        assertEquals(file.toRealPath(), WebAssetHandler.resolveCustomFile(web, "/assets/app.js"));
        assertNull(WebAssetHandler.resolveCustomFile(web, "/assets"));
        assertNull(WebAssetHandler.resolveCustomFile(web, "/assets/missing/app.js"));
        WebAssetHandler.AssetStore store = new WebAssetHandler.AssetStore(web, ignored -> null);
        assertNull(store.load("/not-found.js"));
    }

    @Test
    void rejectsTraversalAbsoluteAndBackslashPaths() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        for (String path : new String[]{"/../secret", "/assets/../../secret", "/..\\secret",
                "//server/share/file", "relative.js", "/bad\0path"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WebAssetHandler.resolveCustomFile(web, path), path);
        }
    }

    @Test
    void directoryLinkCannotEscapeRootEvenForMissingTargets() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.write(outside.resolve("secret.js"), body('s'));
        Path link = web.resolve("linked");
        createDirectoryLink(link, outside);
        try {
            WebAssetHandler.AssetStore store = new WebAssetHandler.AssetStore(web,
                    ignored -> fail("escaping links must not fall back to bundled resources"));
            assertThrows(SecurityException.class, () -> store.load("/linked/secret.js"));
            assertThrows(SecurityException.class, () -> store.load("/linked/missing/app.js"));
        } finally {
            Files.delete(link); // Delete only the link, never traverse the external target.
        }
        assertTrue(Files.exists(outside.resolve("secret.js")));
    }

    @Test
    void directoryLinkInsideRootIsAllowed() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Path target = Files.createDirectory(web.resolve("assets"));
        Path file = Files.write(target.resolve("app.js"), body('a'));
        Path link = web.resolve("alias");
        createDirectoryLink(link, target);
        try {
            assertEquals(file.toRealPath(), WebAssetHandler.resolveCustomFile(web, "/alias/app.js"));
        } finally {
            Files.delete(link);
        }
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Junctions do not require Windows' symlink privilege and exercise toRealPath too.
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private WebAssetHandler.AssetStore bundledStore() {
        return new WebAssetHandler.AssetStore(temporary.resolve("web"), ignored -> new ByteArrayInputStream(body('x')));
    }

    private static byte[] body(char value) {
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static Exchange get(String acceptEncoding) {
        Exchange exchange = new Exchange();
        exchange.request.set("Accept-Encoding", acceptEncoding);
        return exchange;
    }

    private static void serve(WebAssetHandler.AssetStore store, WebAssetHandler.CachedAsset asset,
                              String path, Exchange exchange) throws IOException {
        WebAssetHandler.serveAsset(exchange, asset, path, store,
                ex -> ex.getResponseHeaders().set("Vary", "Origin"));
    }

    private static void assertGzipBody(byte[] expected, Exchange exchange) throws IOException {
        assertEquals(200, exchange.status);
        assertEquals("gzip", exchange.response.getFirst("Content-Encoding"));
        assertTrue(exchange.output.size() < expected.length);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(exchange.output.toByteArray()))) {
            assertArrayEquals(expected, gzip.readAllBytes());
        }
        assertVary(exchange);
    }

    private static void assertVary(Exchange exchange) {
        String vary = String.join(",", exchange.response.get("Vary"));
        assertTrue(vary.contains("Origin"), vary);
        assertTrue(vary.contains("Accept-Encoding"), vary);
    }

    // Minimal HTTP boundary, not a mocked plugin or server. Exercises production response writers.
    static final class Exchange extends HttpExchange {
        final Headers request = new Headers();
        final Headers response = new Headers();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status;
        long length;
        boolean closed;

        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return URI.create("/app.js"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code, long bytes) { status = code; length = bytes; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
