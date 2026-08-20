package com.huidu.musicboxplus.module.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

// Owns the web editor's lifecycle only: config snapshot, HTTP server, session creation and
// cleanup. Every /api route lives in its own handler class; the shared request plumbing lives
// in WebApiSupport.
public class WebEditorServer {
    private static final long RATE_LIMIT_WINDOW_MS = 60000;

    private final MusicBox plugin;
    private final WebConfig config;
    private final WebSessionManager sessionManager;
    private final Gson jsonSerializer;
    private final WebRateLimiter rateLimiter;
    private final WebApiSupport apiSupport;
    private ExecutorService httpExecutor;
    private HttpServer httpServer;
    private java.util.concurrent.ScheduledFuture<?> sessionCleanupFuture;

    public WebEditorServer(MusicBox plugin) {
        this.plugin = plugin;
        this.config = new WebConfig(plugin);
        this.sessionManager = new WebSessionManager(config);
        this.jsonSerializer = createGsonSerializer();
        this.rateLimiter = new WebRateLimiter(config.getRateLimit(), RATE_LIMIT_WINDOW_MS);
        this.apiSupport = new WebApiSupport(plugin, config, sessionManager, jsonSerializer, rateLimiter);
    }

    private Gson createGsonSerializer() {
        return new GsonBuilder()
                .registerTypeAdapter(MusicNote.NoteInstrument.class, new InstrumentJsonAdapter())
                .registerTypeAdapter(MusicNote.class, new NoteJsonAdapter())
                .create();
    }

    public boolean startup() {
        try {
            String bindAddress = config.getBindAddress();
            httpServer = HttpServer.create(new InetSocketAddress(bindAddress, config.getPort()), 0);
            registerHandlers();
            // Bounded queue, not newFixedThreadPool's unbounded one: once the backlog is full a
            // flood is rejected at the door instead of accumulating. This is the guard a
            // per-route semaphore used to claim to provide and could not -- it had one permit per
            // pool thread, so it could never actually refuse anything.
            int webThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
            httpExecutor = new java.util.concurrent.ThreadPoolExecutor(
                    webThreads, webThreads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(REQUEST_BACKLOG),
                    runnable -> {
                        Thread thread = new Thread(runnable);
                        thread.setName("MusicBox-Web-" + thread.threadId());
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
            );
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            scheduleSessionCleanup();
            plugin.getLogger().info("Web editor server started on " + bindAddress + ":" + config.getPort());
            if ("0.0.0.0".equals(bindAddress)) {
                plugin.getLogger().warning("Web editor is bound to ALL network interfaces over PLAIN HTTP ("
                        + bindAddress + ":" + config.getPort() + "). Session tokens and music data travel unencrypted. "
                        + "Only enable bind-all-interfaces behind a TLS reverse proxy on a trusted network.");
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start web editor server: " + e.getMessage(), e);
            return false;
        }
    }

    private static final int REQUEST_BACKLOG = 64;

    private void registerHandlers() {

        httpServer.createContext("/", new WebAssetHandler(plugin, apiSupport::setCorsHeaders, rateLimiter, config));
        httpServer.createContext("/api/music", new MusicApiHandler(apiSupport));
        httpServer.createContext("/api/import", new ImportApiHandler(apiSupport));
        httpServer.createContext("/api/instruments", new InstrumentApiHandler(apiSupport));
        httpServer.createContext("/api/session", new SessionApiHandler(apiSupport));
        httpServer.createContext("/api/settings", new SettingsApiHandler(apiSupport));
    }


    private void scheduleSessionCleanup() {
        sessionCleanupFuture = AsyncTaskManager.getInstance().scheduleAtFixedRate(
            () -> {
                sessionManager.purgeExpiredSessions();
                rateLimiter.cleanup();
            },
            60L, 60L, TimeUnit.SECONDS
        );
    }

    public void shutdown() {
        if (sessionCleanupFuture != null) {
            sessionCleanupFuture.cancel(false);
            sessionCleanupFuture = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            plugin.getLogger().info("Web editor server stopped");
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    public String createSession(UUID playerId, UUID musicId) {
        return sessionManager.create(playerId, musicId);
    }

    public String buildSessionUrl(String sessionId) {
        return String.format("http://%s:%d/?session=%s", config.getHost(), config.getPort(), sessionId);
    }

    public boolean isEnabled() {
        return config.isEnabled() && httpServer != null;
    }

    public WebConfig getConfig() {
        return config;
    }

    public String getServerInfo() {
        return config.getHost() + ":" + config.getPort();
    }
}
