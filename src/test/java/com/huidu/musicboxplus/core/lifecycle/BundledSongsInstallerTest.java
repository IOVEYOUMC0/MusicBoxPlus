package com.huidu.musicboxplus.core.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundledSongsInstallerTest {
    @TempDir
    Path directory;

    @Test
    void legacyKeepsAdministratorContentAndDoesNotRestoreDeletedSongs() throws IOException {
        Path data = directory.resolve("legacy");
        Files.createDirectories(data.resolve("songs"));
        Files.writeString(data.resolve("songs/custom.nbs"), "administrator version");
        Path jar = jar("songs/custom.nbs", "bundled version", "songs/deleted.nbs", "deleted default");

        BundledSongsInstaller.install(data, jar);

        assertEquals("administrator version", Files.readString(data.resolve("songs/custom.nbs")));
        assertFalse(Files.exists(data.resolve("songs/deleted.nbs")));
        assertComplete(data);
        // Completing legacy migration must also preserve deletions on subsequent starts.
        Files.delete(data.resolve("songs/custom.nbs"));
        BundledSongsInstaller.install(data, jar);
        assertFalse(Files.exists(data.resolve("songs/custom.nbs")));
    }

    @Test
    void existingDataFolderWithoutSongsIsAlsoLegacyAndDoesNotReadJar() throws IOException {
        Path data = Files.createDirectory(directory.resolve("legacy-empty"));

        BundledSongsInstaller.install(data, directory.resolve("nonexistent.jar"));

        assertFalse(Files.exists(data.resolve("songs")));
        assertComplete(data);
    }

    @Test
    void firstInstallExtractsNestedSongsAndCommitsCompletion() throws IOException {
        Path data = directory.resolve("fresh");
        Path jar = jar("songs/", "", "songs/one.nbs", "one", "songs/nested/two.nbs", "two",
                "config.yml", "not a song");

        BundledSongsInstaller.install(data, jar);

        assertEquals("one", Files.readString(data.resolve("songs/one.nbs")));
        assertEquals("two", Files.readString(data.resolve("songs/nested/two.nbs")));
        assertFalse(Files.exists(data.resolve("config.yml")));
        assertComplete(data);
        assertNoTemps(data);
    }

    @Test
    void partialFailureLeavesPendingAndRetryPreservesAlreadyExtractedOrEditedSongs() throws IOException {
        Path data = directory.resolve("partial");
        // A file followed by a child beneath that file deterministically fails without relying
        // on permission differences, disk quotas, or platform-specific file locking.
        Path broken = jar("songs/good.nbs", "first", "songs/blocker", "block",
                "songs/blocker/remaining.nbs", "remaining");

        assertThrows(IOException.class, () -> BundledSongsInstaller.install(data, broken));

        assertEquals("first", Files.readString(data.resolve("songs/good.nbs")));
        assertPending(data);
        assertNoTemps(data);
        Files.writeString(data.resolve("songs/good.nbs"), "administrator edit during retry");
        Files.delete(data.resolve("songs/blocker"));
        Path repaired = jar("songs/good.nbs", "replacement", "songs/blocker/remaining.nbs", "remaining");

        BundledSongsInstaller.install(data, repaired);

        assertEquals("administrator edit during retry", Files.readString(data.resolve("songs/good.nbs")));
        assertEquals("remaining", Files.readString(data.resolve("songs/blocker/remaining.nbs")));
        assertComplete(data);
    }

    @Test
    void unreadableJarLeavesRetryIntentThenValidJarCanComplete() throws IOException {
        Path data = directory.resolve("bad-jar");
        Path bad = directory.resolve("bad.jar");
        Files.writeString(bad, "not a zip file");

        assertThrows(IOException.class, () -> BundledSongsInstaller.install(data, bad));
        assertPending(data);
        BundledSongsInstaller.install(data, jar("songs/retry.nbs", "recovered"));

        assertEquals("recovered", Files.readString(data.resolve("songs/retry.nbs")));
        assertComplete(data);
    }

    @Test
    void pendingResumesWithoutOverwritingExistingTargetsOrAdministratorTempFiles() throws IOException {
        Path data = directory.resolve("resume");
        Files.createDirectories(data.resolve("songs"));
        Files.createFile(data.resolve(BundledSongsInstaller.PENDING));
        Files.writeString(data.resolve("songs/existing.nbs"), "administrator");
        Files.writeString(data.resolve("songs/new.nbs.tmp"), "administrator temp");

        BundledSongsInstaller.install(data, jar("songs/existing.nbs", "default", "songs/new.nbs", "new"));

        assertEquals("administrator", Files.readString(data.resolve("songs/existing.nbs")));
        assertEquals("administrator temp", Files.readString(data.resolve("songs/new.nbs.tmp")));
        assertEquals("new", Files.readString(data.resolve("songs/new.nbs")));
        assertComplete(data);
        assertNoTemps(data);
    }

    @Test
    void completedWinsOverStalePendingWithoutRestoringDeletedSongs() throws IOException {
        Path data = Files.createDirectory(directory.resolve("completed"));
        Files.createFile(data.resolve(BundledSongsInstaller.COMPLETED));
        Files.createFile(data.resolve(BundledSongsInstaller.PENDING));

        BundledSongsInstaller.install(data, directory.resolve("unreadable.jar"));

        assertComplete(data);
        assertFalse(Files.exists(data.resolve("songs")));
    }

    @Test
    void maliciousJarPathsNeverEscapeSongsRootAndKeepPending() throws IOException {
        String[] hostile = {"songs/../outside.nbs", "songs/nested/../../outside.nbs",
                "songs//absolute.nbs", "songs/./dot.nbs", "songs/..\\outside.nbs",
                "songs/C:/drive.nbs", "songs/nested/file.nbs:stream"};
        for (int i = 0; i < hostile.length; i++) {
            Path data = directory.resolve("hostile-" + i);
            Path malicious = jar(hostile[i], "bad");

            assertThrows(IOException.class, () -> BundledSongsInstaller.install(data, malicious));

            assertFalse(Files.exists(data.resolve("outside.nbs")));
            assertFalse(Files.exists(directory.resolve("outside.nbs")));
            assertPending(data);
            try (var paths = Files.walk(data.resolve("songs"))) {
                assertEquals(1, paths.count(), "invalid entry must not create paths");
            }
        }
    }

    @Test
    void symbolicLinkParentCannotRedirectExtractionOutsideSongs() throws IOException {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path data = directory.resolve("symlink-parent");
        Files.createDirectories(data.resolve("songs"));
        Files.createFile(data.resolve(BundledSongsInstaller.PENDING));
        createSymlinkOrSkip(data.resolve("songs/nested"), outside);

        assertThrows(IOException.class, () -> BundledSongsInstaller.install(data,
                jar("songs/nested/escape.nbs", "bad")));

        assertFalse(Files.exists(outside.resolve("escape.nbs")));
        assertPending(data);
    }

    @Test
    void symbolicLinkTargetIsNeitherFollowedNorReplaced() throws IOException {
        Path outside = directory.resolve("administrator.nbs");
        Files.writeString(outside, "administrator");
        Path data = directory.resolve("symlink-target");
        Files.createDirectories(data.resolve("songs"));
        Files.createFile(data.resolve(BundledSongsInstaller.PENDING));
        Path link = data.resolve("songs/link.nbs");
        createSymlinkOrSkip(link, outside);

        assertThrows(IOException.class, () -> BundledSongsInstaller.install(data, jar("songs/link.nbs", "bad")));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("administrator", Files.readString(outside));
        assertPending(data);
    }

    private Path jar(String... entries) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            contents.put(entries[i], entries[i + 1]);
        }
        Path jar = Files.createTempFile(directory, "bundle-", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : contents.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static void assertComplete(Path data) {
        assertTrue(Files.isRegularFile(data.resolve(BundledSongsInstaller.COMPLETED)));
        assertFalse(Files.exists(data.resolve(BundledSongsInstaller.PENDING)));
    }

    private static void assertPending(Path data) {
        assertTrue(Files.isRegularFile(data.resolve(BundledSongsInstaller.PENDING)));
        assertFalse(Files.exists(data.resolve(BundledSongsInstaller.COMPLETED)));
    }

    private static void assertNoTemps(Path data) throws IOException {
        try (var paths = Files.walk(data)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".bundled-song-")));
        }
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Platform does not permit symbolic links: " + e);
        }
    }
}
