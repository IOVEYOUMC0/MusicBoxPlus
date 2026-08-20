package com.huidu.musicboxplus.common.utils;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;

public final class StorageAccess {
    private StorageAccess() {
    }

    public static boolean canWriteTo(File target) {
        if (target == null) {
            return false;
        }
        if (target.exists()) {
            return target.canWrite();
        }
        File parent = target.getParentFile();
        while (parent != null && !parent.exists()) {
            parent = parent.getParentFile();
        }
        return parent != null && parent.canWrite();
    }

    public static boolean isPermissionIssue(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AccessDeniedException) {
                return true;
            }
            if (current instanceof FileNotFoundException && containsPermissionText(current.getMessage())) {
                return true;
            }
            if (current instanceof IOException && containsPermissionText(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsPermissionText(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("permission denied") || normalized.contains("access is denied");
    }

    public static byte[] readBundledResource(JavaPlugin plugin, String resourcePath) throws IOException {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                throw new IOException("Bundled resource not found: " + resourcePath);
            }
            return stream.readAllBytes();
        }
    }

    public static void ensureParentDirectories(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || parent.exists()) {
            return;
        }
        Files.createDirectories(parent.toPath());
    }
}
