package com.huidu.musicboxplus.core.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a zip of the user-owned parts of the plugin data folder.
 *
 * <p>Pure JDK and static on purpose: the backup contract must be testable without a running
 * server, and none of its inputs come from the plugin singleton.
 */
public final class BackupService {
    private static final String ARCHIVE_PREFIX = "musicbox-backup-";
    private static final String ARCHIVE_SUFFIX = ".zip";
    private static final Pattern ARCHIVE_NAME = Pattern.compile(
            "musicbox-backup-(\\d{8}-\\d{6})(?:-(\\d{1,18}))?\\.zip");
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    private static final List<String> SQLITE_FILES =
            List.of("data.db", "data.db-wal", "data.db-shm");

    private BackupService() {
    }

    /**
     * Creates one archive and applies the retention limit.
     *
     * @param keep number of archives to retain, or a non-positive value to disable pruning
     */
    public static BackupResult create(
            File dataFolder, File backupDir, boolean mysqlBackend, int keep) throws IOException {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(backupDir, "backupDir");

        Path dataRoot = resolveDataRoot(dataFolder);
        Path backupRoot = resolveBackupRoot(backupDir);
        Path backupRealRoot = backupRoot.toRealPath();
        // If the destination is the data root itself, excluding that whole subtree would produce
        // an empty archive. The whitelist already keeps .zip archives out in that layout.
        Path excludedBackupRoot = backupRealRoot.startsWith(dataRoot)
                && !backupRealRoot.equals(dataRoot) ? backupRealRoot : null;

        List<Path> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean databaseFileIncluded =
                collectFiles(dataRoot, excludedBackupRoot, mysqlBackend, files, skipped);

        Path archive = reserveArchive(backupRoot, LocalDateTime.now(ZoneId.systemDefault()));
        int entries;
        try {
            entries = writeArchive(dataRoot, archive, files);
        } catch (IOException failure) {
            deleteAfterFailure(archive, failure);
            throw failure;
        } catch (RuntimeException failure) {
            deleteAfterFailure(archive, failure);
            throw failure;
        }

        long bytes = Files.size(archive);
        int pruned = prune(backupRoot, keep);
        return new BackupResult(
                archive, bytes, entries, databaseFileIncluded, skipped, pruned);
    }

    private static Path resolveDataRoot(File dataFolder) throws IOException {
        // Resolve the root once so walked paths and archive entry names share one spelling.
        Path root = dataFolder.toPath().toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Data folder is not a directory: " + root);
        }
        return root;
    }

    private static Path resolveBackupRoot(File backupDir) throws IOException {
        Path root = backupDir.toPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
        // Keep the caller's spelling for the returned archive path; the canonical form is used
        // only for the "is this folder inside the data folder" comparison.
        return root;
    }

    private static boolean collectFiles(
            Path dataRoot,
            Path excludedBackupRoot,
            boolean mysqlBackend,
            List<Path> files,
            List<String> skipped) throws IOException {
        Set<Path> seen = new HashSet<>();
        collectTopLevelYml(dataRoot, excludedBackupRoot, seen, files, skipped);
        collectTree(dataRoot.resolve("lang"), dataRoot, excludedBackupRoot, seen, files, skipped);
        collectTree(dataRoot.resolve("songs"), dataRoot, excludedBackupRoot, seen, files, skipped);

        boolean databaseFileIncluded = false;
        if (!mysqlBackend) {
            // WAL and SHM belong to the same SQLite snapshot; a data.db copied without them can
            // lose pages that only exist in the sidecars.
            for (String name : SQLITE_FILES) {
                Path databaseFile = dataRoot.resolve(name);
                BasicFileAttributes attributes = readAttributesIfExists(databaseFile);
                if (attributes != null) {
                    databaseFileIncluded |= acceptFile(
                            databaseFile, attributes, dataRoot, excludedBackupRoot, seen, files, skipped);
                }
            }
        }
        return databaseFileIncluded;
    }

    private static void collectTopLevelYml(
            Path dataRoot,
            Path excludedBackupRoot,
            Set<Path> seen,
            List<Path> files,
            List<String> skipped) throws IOException {
        Files.walkFileTree(dataRoot, Set.of(), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().endsWith(".yml")) {
                    acceptFile(file, attributes, dataRoot, excludedBackupRoot, seen, files, skipped);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void collectTree(
            Path start,
            Path dataRoot,
            Path excludedBackupRoot,
            Set<Path> seen,
            List<Path> files,
            List<String> skipped) throws IOException {
        // NOFOLLOW_LINKS is the walkFileTree default: a linked directory is visited as a file and
        // never descended into, so a link cannot pull data from outside the data folder.
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) {
                Path absolute = directory.toAbsolutePath().normalize();
                if (excludedBackupRoot != null && absolute.startsWith(excludedBackupRoot)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                acceptFile(file, attributes, dataRoot, excludedBackupRoot, seen, files, skipped);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static BasicFileAttributes readAttributesIfExists(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean acceptFile(
            Path file,
            BasicFileAttributes attributes,
            Path dataRoot,
            Path excludedBackupRoot,
            Set<Path> seen,
            List<Path> files,
            List<String> skipped) {
        // Symlinks are reported by NOFOLLOW attributes; never archive their target.
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            return false;
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (excludedBackupRoot != null && absolute.startsWith(excludedBackupRoot)) {
            return false;
        }
        if (!seen.add(absolute)) {
            return false;
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            // Skip before opening the file, so an oversized song cannot exhaust the heap.
            skipped.add(entryName(dataRoot, absolute));
            return false;
        }
        files.add(absolute);
        return true;
    }

    private static Path reserveArchive(Path backupRoot, LocalDateTime now) throws IOException {
        String timestamp = ARCHIVE_TIMESTAMP.format(now);
        for (int sequence = 1; ; sequence++) {
            String suffix = sequence == 1 ? "" : "-" + sequence;
            Path candidate = backupRoot.resolve(
                    ARCHIVE_PREFIX + timestamp + suffix + ARCHIVE_SUFFIX);
            try {
                // CREATE_NEW reserves the name atomically, so an existing archive is never replaced
                // and two writers in the same second cannot choose the same file.
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // A same-second archive already owns this name; try the next suffix.
            }
        }
    }

    private static int writeArchive(Path dataRoot, Path archive, List<Path> files)
            throws IOException {
        int entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(
                        archive, StandardOpenOption.WRITE)),
                StandardCharsets.UTF_8)) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(entryName(dataRoot, file)));
                try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
                entries++;
            }
        }
        return entries;
    }

    private static String entryName(Path dataRoot, Path file) {
        StringBuilder name = new StringBuilder();
        for (Path part : dataRoot.relativize(file)) {
            if (name.length() > 0) {
                name.append('/');
            }
            name.append(part);
        }
        return name.toString();
    }

    private static void deleteAfterFailure(Path archive, Throwable failure) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException cleanupFailure) {
            // Keep the original write failure primary; the leftover path is still useful.
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static int prune(Path backupRoot, int keep) throws IOException {
        if (keep <= 0) {
            return 0;
        }
        List<Path> archives;
        try (Stream<Path> listing = Files.list(backupRoot)) {
            archives = listing
                    .filter(Files::isRegularFile)
                    // Only archives this service names are candidates; other zips in the folder
                    // are not ours to delete.
                    .filter(path -> isArchiveName(path.getFileName().toString()))
                    // A plain string sort puts "-2.zip" before ".zip" ('-' < '.'), so compare
                    // the timestamp and the numeric same-second suffix separately.
                    .sorted(Comparator.comparing(BackupService::archiveSortKey))
                    .toList();
        }
        int excess = archives.size() - keep;
        if (excess <= 0) {
            return 0;
        }
        for (int index = 0; index < excess; index++) {
            Files.delete(archives.get(index));
        }
        return excess;
    }

    private static boolean isArchiveName(String name) {
        return ARCHIVE_NAME.matcher(name).matches();
    }

    private static String archiveSortKey(Path path) {
        Matcher matcher = ARCHIVE_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return path.getFileName().toString();
        }
        long sequence = matcher.group(2) == null ? 1L : Long.parseLong(matcher.group(2));
        // Fixed width makes the whole key sortable while preserving numeric suffix order.
        return matcher.group(1) + "-" + String.format(Locale.ROOT, "%018d", sequence);
    }

    /** Immutable result of one backup run. */
    public record BackupResult(
            Path archive,
            long bytes,
            int entries,
            boolean databaseFileIncluded,
            List<String> skipped,
            int pruned) {
        public BackupResult {
            archive = Objects.requireNonNull(archive, "archive");
            skipped = List.copyOf(skipped);
        }
    }
}
