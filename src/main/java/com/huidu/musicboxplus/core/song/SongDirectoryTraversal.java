package com.huidu.musicboxplus.core.song;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One directory listing per container, with one shared session for an entire root load.
 * The caller lists the root first, then passes the SAME session to each returned directory.
 * Results contain only immediate children; this helper never schedules recursive work.
 */
public final class SongDirectoryTraversal {
    public static final Limits DEFAULT_LIMITS = new Limits(64, 4096, 50_000);

    private SongDirectoryTraversal() {
    }

    /** Root depth is zero; the directory count includes the root itself. */
    public record Limits(int maxDepth, int maxDirectories, int maxSongs) {
        public Limits {
            if (maxDepth < 0 || maxDirectories < 1 || maxSongs < 0) {
                throw new IllegalArgumentException("Require depth >= 0, directories >= 1, songs >= 0");
            }
        }
    }

    /** A snapshot, including the lists themselves, is immutable. Directory paths are canonical. */
    public record Result(List<File> songs, List<File> directories) {
        public Result {
            songs = List.copyOf(songs);
            directories = List.copyOf(directories);
        }
    }

    public static Session open(File root) throws IOException {
        return open(root, DEFAULT_LIMITS);
    }

    public static Session open(File root, Limits limits) throws IOException {
        return open(root, limits, Files::newDirectoryStream);
    }

    // Package-local seam for counting listings and injecting open/iteration/close failures.
    @FunctionalInterface
    interface DirectoryListing {
        DirectoryStream<Path> open(Path directory) throws IOException;
    }

    static Session open(File root, Limits limits, DirectoryListing listing) throws IOException {
        return new Session(root, limits, listing);
    }

    /**
     * Thread safe, root-bound state. All directory claims and limits share the same lock.
     * Repeated directory claims are errors, not empty results; any failure invalidates the
     * session, so callers must propagate IOException and must not publish a partial root load.
     */
    public static final class Session {
        private final Path requestedRoot;
        private final Path realRoot;
        private final Limits limits;
        private final DirectoryListing listing;
        private final Map<Path, Integer> discovered = new HashMap<>();
        private final Set<Path> visited = new HashSet<>();
        private int songCount;
        private IOException failure;

        private Session(File root, Limits limits, DirectoryListing listing) throws IOException {
            this.requestedRoot = Objects.requireNonNull(root, "root").toPath().toAbsolutePath().normalize();
            this.limits = Objects.requireNonNull(limits, "limits");
            this.listing = Objects.requireNonNull(listing, "listing");
            // Resolve the root ONCE. Root aliases (including Windows short names and a root
            // symlink/junction) are allowed; all descendant discovery uses this pinned path.
            this.realRoot = requestedRoot.toRealPath();
            verifyDirectory(realRoot);
            discovered.put(realRoot, 0);
        }

        public File root() {
            return realRoot.toFile();
        }

        /**
         * Lists a previously discovered directory exactly once. An original root alias and
         * its canonical path are both accepted. Ordinary .nbs files match case insensitively;
         * a directory named *.nbs is a directory. Directory redirects are rejected, including
         * links pointing inside the root. File links are allowed only to regular files whose
         * real path is inside the pinned root. Broken links and unreadable entries fail.
         */
        public synchronized Result list(File folder) throws IOException {
            Objects.requireNonNull(folder, "folder");
            if (failure != null) {
                throw new IOException("Traversal session already failed for " + realRoot, failure);
            }
            try {
                Path directory = resolveDirectory(folder.toPath());
                Integer depth = discovered.get(directory);
                if (depth == null) {
                    throw new IOException("Directory was not discovered in this session: " + folder);
                }
                if (!visited.add(directory)) {
                    throw new IOException("Directory was already listed in this session: " + directory);
                }
                verifyDirectory(directory);
                List<File> songs = new ArrayList<>();
                List<File> directories = new ArrayList<>();
                try (DirectoryStream<Path> entries = listing.open(directory)) {
                    for (Path entry : entries) {
                        Path child = entry.toAbsolutePath().normalize();
                        if (!directory.equals(child.getParent())) {
                            throw new IOException("Listing returned a non-child path: " + entry);
                        }
                        BasicFileAttributes attributes = Files.readAttributes(
                                child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attributes.isSymbolicLink()) {
                            BasicFileAttributes target = Files.readAttributes(child, BasicFileAttributes.class);
                            if (target.isDirectory()) {
                                throw new IOException("Directory symbolic link is not allowed: " + child);
                            }
                            if (target.isRegularFile() && isSong(child)) {
                                addSong(child, songs);
                            }
                        } else if (attributes.isDirectory()) {
                            Path realChild = verifyDirectory(child);
                            requireInsideRoot(realChild);
                            if (discovered.containsKey(realChild)) {
                                throw new IOException("Repeated directory real path: " + child + " -> " + realChild);
                            }
                            if (depth >= limits.maxDepth()) {
                                throw new IOException("Directory depth limit " + limits.maxDepth() + " exceeded at " + child);
                            }
                            if (discovered.size() >= limits.maxDirectories()) {
                                throw new IOException("Directory count limit " + limits.maxDirectories() + " exceeded at " + child);
                            }
                            discovered.put(realChild, depth + 1);
                            directories.add(realChild.toFile());
                        } else if (attributes.isRegularFile() && isSong(child)) {
                            addSong(child, songs);
                        } else if (attributes.isOther()) {
                            // Some providers expose reparse points as "other". Do not silently
                            // discard a possible redirected directory or an unreadable song.
                            throw new IOException("Unsupported filesystem entry: " + child);
                        }
                    }
                }
                return new Result(songs, directories);
            } catch (DirectoryIteratorException e) {
                failure = new IOException("Cannot iterate directory " + folder, e.getCause());
                throw failure;
            } catch (IOException e) {
                failure = new IOException("Cannot traverse " + folder + ": " + e.getMessage(), e);
                throw failure;
            } catch (SecurityException e) {
                failure = new IOException("Access denied while traversing " + folder, e);
                throw failure;
            }
        }

        private Path resolveDirectory(Path folder) throws IOException {
            Path requested = folder.toAbsolutePath().normalize();
            Path pinned;
            if (requested.startsWith(requestedRoot)) {
                pinned = realRoot.resolve(requestedRoot.relativize(requested));
            } else if (requested.startsWith(realRoot)) {
                pinned = requested;
            } else {
                throw new IOException("Directory path is outside the session root: " + requested);
            }
            Path real = requested.toRealPath();
            requireInsideRoot(real);
            // NOFOLLOW canonicalizes ordinary spelling/Windows 8.3 names without accepting a
            // redirected descendant. Do not compare realRoot against the original root spelling.
            if (!real.equals(pinned.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Directory redirection or changed root: " + requested + " -> " + real);
            }
            return real;
        }

        private void addSong(Path song, List<File> songs) throws IOException {
            requireInsideRoot(song.toRealPath());
            if (songCount >= limits.maxSongs()) {
                throw new IOException("Song count limit " + limits.maxSongs() + " exceeded at " + song);
            }
            songCount++;
            songs.add(song.toFile());
        }

        private void requireInsideRoot(Path real) throws IOException {
            if (!real.startsWith(realRoot)) {
                throw new IOException("Real path escapes root " + realRoot + ": " + real);
            }
        }
    }

    private static boolean isSong(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs");
    }

    private static Path verifyDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Not an ordinary directory: " + directory);
        }
        Path real = directory.toRealPath();
        Path noFollow = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(noFollow)) {
            throw new IOException("Directory redirection is not allowed: " + directory + " -> " + real);
        }
        return real;
    }
}
