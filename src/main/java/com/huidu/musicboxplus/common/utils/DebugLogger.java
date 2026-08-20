package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;

import java.util.logging.Level;

public final class DebugLogger {
    private DebugLogger() {
    }

    public static boolean isDebugEnabled() {
        MusicBox plugin = MusicBox.getInstance();
        if (plugin == null || plugin.getConfigObject() == null) {
            return false;
        }
        return plugin.getConfigObject().isDebug();
    }

    public static void debug(String message) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().info("[DEBUG] " + message);
        }
    }

    public static void debug(String format, Object ... args) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().info("[DEBUG] " + String.format(format, args));
        }
    }

    public static void debugWarning(String message) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().warning("[DEBUG] " + message);
        }
    }

    public static void debugError(String message, Throwable throwable) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().log(Level.SEVERE, "[DEBUG] " + message, throwable);
        }
    }

    public static void debugPerformance(String operation, long startTime) {
        if (DebugLogger.isDebugEnabled()) {
            long elapsed = System.currentTimeMillis() - startTime;
            MusicBox.getInstance().getLogger().info("[DEBUG-PERF] " + operation + " \u8017\u65f6: " + elapsed + "ms");
        }
    }

    public static void debugGUI(String message) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().info("[DEBUG-GUI] " + message);
        }
    }

    public static void debugCache(String message) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().info("[DEBUG-CACHE] " + message);
        }
    }

    public static void debugSong(String message) {
        if (DebugLogger.isDebugEnabled()) {
            MusicBox.getInstance().getLogger().info("[DEBUG-SONG] " + message);
        }
    }
}

