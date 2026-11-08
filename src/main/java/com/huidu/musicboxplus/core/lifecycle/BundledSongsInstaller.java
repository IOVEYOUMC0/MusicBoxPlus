package com.huidu.musicboxplus.core.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Filesystem-only first-install protocol; deliberately independent of Bukkit. */
final class BundledSongsInstaller {
    static final String COMPLETED = ".bundled-songs-extracted";
    static final String PENDING = ".bundled-songs-pending";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private BundledSongsInstaller() {
    }

    static void install(Path folder, Path pluginJar) throws IOException {
        Path data = folder.toAbsolutePath().normalize();
        // Capture this BEFORE creating the data folder (or saving config.yml). An unmarked,
        // existing installation is legacy, not an interrupted first installation.
        boolean legacy = Files.exists(data, NOFOLLOW)
                || Files.exists(data.resolve("songs"), NOFOLLOW);
        safeDirectories(data);
        Path completed = data.resolve(COMPLETED);
        Path pending = data.resolve(PENDING);
        if (markerExists(completed)) {
            // A crash after committing completion but before removing pending is already done.
            if (markerExists(pending)) {
                Files.delete(pending);
            }
            return;
        }
        boolean resume = markerExists(pending);
        if (!resume && legacy) {
            createMarker(completed);
            return;
        }
        if (!resume) {
            // Never start writing songs unless the durable retry intent has been recorded.
            createMarker(pending);
        }
        extract(data.resolve("songs"), pluginJar);
        createMarker(completed);
        Files.deleteIfExists(pending);
    }

    private static boolean markerExists(Path marker) throws IOException {
        if (!Files.exists(marker, NOFOLLOW)) {
            return false;
        }
        if (!Files.isRegularFile(marker, NOFOLLOW)) {
            throw new IOException("Invalid bundled song marker: " + marker);
        }
        return true;
    }

    private static void createMarker(Path marker) throws IOException {
        safeDirectories(marker.getParent());
        try {
            Files.createFile(marker);
        } catch (FileAlreadyExistsException e) {
            if (!markerExists(marker)) {
                throw e;
            }
        }
    }

    private static void extract(Path songs, Path pluginJar) throws IOException {
        safeDirectories(songs);
        IOException failure = null;
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("songs/")) {
                    continue;
                }
                try {
                    Path target = targetFor(songs, entry.getName());
                    if (entry.isDirectory()) {
                        safeDirectories(target);
                    } else {
                        if (target.equals(songs)) {
                            throw new IOException("Song entry names the songs root: " + entry.getName());
                        }
                        extractFile(jar, entry, target);
                    }
                } catch (IOException e) {
                    if (failure == null) {
                        failure = new IOException("Bundled song extraction is incomplete");
                    }
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path targetFor(Path songs, String name) throws IOException {
        // Jar paths always use '/'. Reject rather than normalize hostile components, including
        // Windows drive/ADS names and backslashes even when tests run on another OS.
        String relative = name.substring("songs/".length());
        if (relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0 || relative.indexOf('\0') >= 0) {
            throw new IOException("Unsafe bundled song path: " + name);
        }
        if (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        if (relative.isEmpty()) {
            return songs;
        }
        for (String component : relative.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe bundled song path: " + name);
            }
        }
        try {
            Path target = songs.resolve(relative).normalize();
            if (!target.startsWith(songs)) {
                throw new IOException("Bundled song escapes songs directory: " + name);
            }
            return target;
        } catch (InvalidPathException e) {
            throw new IOException("Invalid bundled song path: " + name, e);
        }
    }

    private static void extractFile(JarFile jar, JarEntry entry, Path target) throws IOException {
        Path parent = target.getParent();
        safeDirectories(parent);
        if (existingSong(target)) {
            return;
        }
        // Only this invocation owns this random, exclusively-created sibling. Never delete a
        // guessed '<song>.tmp', which may belong to an administrator or another extraction.
        Path temp = Files.createTempFile(parent, ".bundled-song-", ".tmp");
        Object tempKey = Files.readAttributes(temp, BasicFileAttributes.class, NOFOLLOW).fileKey();
        boolean moved = false;
        try {
            try (InputStream in = jar.getInputStream(entry);
                 OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                         StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW)) {
                in.transferTo(out);
            }
            safeDirectories(parent);
            if (!existingSong(target)) {
                try {
                    // No REPLACE_EXISTING, and no ATOMIC_MOVE: the latter may replace a racing
                    // destination even without REPLACE_EXISTING on some providers.
                    Files.move(temp, target);
                    moved = true;
                } catch (FileAlreadyExistsException e) {
                    if (!existingSong(target)) {
                        throw e;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            try {
                cleanupTemp(parent, temp, tempKey);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        if (!moved) {
            cleanupTemp(parent, temp, tempKey);
        }
    }

    private static void cleanupTemp(Path parent, Path temp, Object expectedKey) throws IOException {
        // Recheck before cleanup too: do not follow a replaced parent symlink to delete elsewhere.
        safeDirectories(parent);
        if (Files.exists(temp, NOFOLLOW)) {
            BasicFileAttributes attributes = Files.readAttributes(temp, BasicFileAttributes.class, NOFOLLOW);
            if (!attributes.isRegularFile()
                    || (expectedKey != null && !expectedKey.equals(attributes.fileKey()))) {
                throw new IOException("Temporary song file was replaced; refusing cleanup: " + temp);
            }
            Files.delete(temp);
        }
    }

    private static boolean existingSong(Path target) throws IOException {
        if (!Files.exists(target, NOFOLLOW)) {
            return false;
        }
        if (!Files.isRegularFile(target, NOFOLLOW)) {
            throw new IOException("Bundled song destination is not a regular file: " + target);
        }
        return true;
    }

    private static void safeDirectories(Path directory) throws IOException {
        // Check every ancestor, not just the final path; toRealPath additionally rejects junctions
        // and other redirected directories. Repeat before placement and cleanup.
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException ignored) {
                // Validate the existing object below, without following links.
            }
            if (!Files.isDirectory(current, NOFOLLOW) || Files.isSymbolicLink(current)
                    || !current.toRealPath().equals(current.toRealPath(NOFOLLOW))) {
                throw new IOException("Unsafe bundled song directory: " + current);
            }
        }
    }
}
