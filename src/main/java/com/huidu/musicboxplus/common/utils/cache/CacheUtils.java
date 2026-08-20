package com.huidu.musicboxplus.common.utils.cache;

import com.huidu.musicboxplus.common.utils.AsyncTaskManager;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class CacheUtils {
    private static final Logger LOGGER = Logger.getLogger("MusicBox-Cache");
    private static final ConcurrentHashMap<String, CacheCleaner> cacheCleaners = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    private CacheUtils() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        AsyncTaskManager.getInstance().scheduleAtFixedRate(CacheUtils::cleanupAllExpiredCaches, 1L, 1L, TimeUnit.MINUTES);
        LOGGER.info("Cache manager initialized with automatic cleanup");
    }

    public static synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;
        CacheUtils.clearAllCaches();
        LOGGER.info("Cache manager shutdown complete");
    }

    public static void registerCacheCleaner(CacheCleaner cleaner) {
        if (cleaner == null) {
            return;
        }
        String name = cleaner.getCacheName();
        cacheCleaners.put(name, cleaner);
        LOGGER.fine("Registered cache cleaner: " + name);
    }

    public static void unregisterCacheCleaner(String name) {
        if (name == null) {
            return;
        }
        cacheCleaners.remove(name);
        LOGGER.fine("Unregistered cache cleaner: " + name);
    }

    public static void clearAllCaches() {
        LOGGER.info("Clearing all caches...");
        cacheCleaners.values().forEach(cleaner -> {
            try {
                cleaner.clearCache();
                LOGGER.fine("Cleared cache: " + cleaner.getCacheName());
            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to clear cache: " + cleaner.getCacheName(), e);
            }
        });
    }

    private static void cleanupAllExpiredCaches() {
        cacheCleaners.values().forEach(cleaner -> {
            try {
                if (cleaner instanceof ExpiringCacheCleaner) {
                    ((ExpiringCacheCleaner)cleaner).cleanupExpired();
                }
            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cleanup expired cache: " + cleaner.getCacheName(), e);
            }
        });
    }

    public static Collection<String> getAllCacheStats() {
        return cacheCleaners.values().stream().map(CacheCleaner::getCacheStats).collect(Collectors.toList());
    }

    public static int getRegisteredCleanerCount() {
        return cacheCleaners.size();
    }






}
