package com.huidu.musicboxplus.core.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// BackupService is deliberately independent of Bukkit, so these tests drive it with ordinary
// folders and read the result back as a real zip instead of trusting the in-memory return value.
class BackupServiceTest {

    @Test
    void packsOnlyWhitelistedFilesWithRelativeEntryNames(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectory(root.resolve("backups"));
        Files.writeString(data.resolve("ignored.txt"), "ignored", StandardCharsets.UTF_8);
        Files.createDirectories(data.resolve("lang"));
        Files.writeString(
                data.resolve("lang/en.yml"), "language", StandardCharsets.UTF_8);
        Path album = Files.createDirectories(data.resolve("songs/album"));
        Files.writeString(album.resolve("song.nbs"), "song", StandardCharsets.UTF_8);
        Files.write(data.resolve("data.db"), new byte[]{1, 2, 3});
        Files.writeString(data.resolve("data.db-wal"), "wal", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("data.db-shm"), "shm", StandardCharsets.UTF_8);

        BackupService.BackupResult result =
                BackupService.create(data.toFile(), backups.toFile(), false, 0);

        Set<String> expected = Set.of(
                "config.yml",
                "lang/en.yml",
                "songs/album/song.nbs",
                "data.db",
                "data.db-wal",
                "data.db-shm");
        assertEquals(expected, archiveEntries(result.archive()));
        assertEquals(expected.size(), result.entries());
        assertEquals(Files.size(result.archive()), result.bytes());
        assertTrue(result.databaseFileIncluded());
        assertTrue(result.skipped().isEmpty());
        assertEquals(0, result.pruned());
        // The service canonicalises only for the inside-data-root check; the returned path keeps
        // the caller's spelling. Compare real paths so Windows short and long names both match.
        assertTrue(result.archive().toRealPath().startsWith(backups.toRealPath()));
        assertTrue(result.archive().getFileName().toString()
                .matches("musicbox-backup-\\d{8}-\\d{6}\\.zip"));
    }

    @Test
    void mysqlBackendOmitsDatabaseFiles(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectory(root.resolve("backups"));
        Files.write(data.resolve("data.db"), new byte[]{1});
        Files.writeString(data.resolve("data.db-wal"), "wal", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("data.db-shm"), "shm", StandardCharsets.UTF_8);

        BackupService.BackupResult result =
                BackupService.create(data.toFile(), backups.toFile(), true, 0);

        assertFalse(result.databaseFileIncluded());
        assertEquals(Set.of("config.yml"), archiveEntries(result.archive()));
        assertEquals(1, result.entries());
    }

    @Test
    void keepTwoDeletesTheOldestArchive(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectory(root.resolve("backups"));
        Path oldest = Files.createFile(
                backups.resolve("musicbox-backup-20200101-000000.zip"));
        Path previous = Files.createFile(
                backups.resolve("musicbox-backup-20200101-000001.zip"));

        BackupService.BackupResult result =
                BackupService.create(data.toFile(), backups.toFile(), false, 2);

        assertFalse(Files.exists(oldest), "the oldest archive must be pruned");
        assertTrue(Files.exists(previous), "the newer existing archive must survive");
        assertTrue(Files.exists(result.archive()), "the archive just created must survive");
        assertEquals(1, result.pruned());
        assertEquals(2, countArchives(backups));
    }

    @Test
    void retentionComparesSameSecondSuffixesNumerically(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectory(root.resolve("backups"));
        // A plain filename sort puts "-2" before ".zip"; the "-2" archive is actually newer.
        Path base = Files.createFile(
                backups.resolve("musicbox-backup-20200101-000000.zip"));
        Path second = Files.createFile(
                backups.resolve("musicbox-backup-20200101-000000-2.zip"));

        BackupService.BackupResult result =
                BackupService.create(data.toFile(), backups.toFile(), false, 2);

        assertFalse(Files.exists(base), "the base archive is the oldest in its second");
        assertTrue(Files.exists(second), "the suffixed archive is newer than the base");
        assertTrue(Files.exists(result.archive()), "the archive just created must survive");
        assertEquals(1, result.pruned());
    }

    @Test
    void backupFolderInsideDataFolderIsNotArchived(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectories(data.resolve("songs/backups"));
        Path stale = Files.createFile(
                backups.resolve("musicbox-backup-20200101-000000.zip"));
        Files.writeString(
                data.resolve("songs/keep.nbs"), "song", StandardCharsets.UTF_8);

        BackupService.BackupResult result =
                BackupService.create(data.toFile(), backups.toFile(), false, 0);

        Set<String> entries = archiveEntries(result.archive());
        assertEquals(Set.of("config.yml", "songs/keep.nbs"), entries);
        assertTrue(Files.exists(stale), "the old archive inside the backup folder must stay");
        // Resolve both sides so this also passes when @TempDir and the service disagree on
        // Windows 8.3 versus long-name spelling.
        assertEquals(backups.toRealPath(), result.archive().getParent().toRealPath());
    }

    @Test
    void twoCreatesInTheSameSecondUseDifferentNames(@TempDir Path root) throws Exception {
        Path data = createDataFolder(root);
        Path backups = Files.createDirectory(root.resolve("backups"));

        BackupService.BackupResult first =
                BackupService.create(data.toFile(), backups.toFile(), false, 0);
        BackupService.BackupResult second =
                BackupService.create(data.toFile(), backups.toFile(), false, 0);

        assertNotEquals(first.archive().getFileName(), second.archive().getFileName());
        assertTrue(Files.isRegularFile(first.archive()));
        assertTrue(Files.isRegularFile(second.archive()));
        assertTrue(second.archive().getFileName().toString()
                .matches("musicbox-backup-\\d{8}-\\d{6}(-\\d+)?\\.zip"));
    }

    private static Path createDataFolder(Path root) throws IOException {
        Path data = Files.createDirectory(root.resolve("data"));
        Files.writeString(
                data.resolve("config.yml"), "config: true\n", StandardCharsets.UTF_8);
        return data;
    }

    private static Set<String> archiveEntries(Path archive) throws IOException {
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements()) {
                entries.add(names.nextElement().getName());
            }
        }
        return entries;
    }

    private static long countArchives(Path backupFolder) throws IOException {
        try (var files = Files.list(backupFolder)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip")).count();
        }
    }
}
