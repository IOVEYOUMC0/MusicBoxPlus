package com.huidu.musicboxplus.core.song;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SongDirectoryTraversalTest {
    @TempDir
    Path temporary;

    @Test
    void classifiesOnlyImmediateChildrenAndListsEveryDirectoryOnce() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("songs"));
        Path nested = Files.createDirectory(root.resolve("nested.nbs"));
        Files.write(root.resolve("lower.nbs"), new byte[0]);
        Files.write(root.resolve("UPPER.NBS"), new byte[0]);
        Files.write(root.resolve("mixed.NbS"), new byte[0]);
        Files.write(root.resolve("ignore.txt"), new byte[0]);
        Files.write(nested.resolve("inner.nbs"), new byte[0]);
        AtomicInteger listings = new AtomicInteger();
        var session = SongDirectoryTraversal.open(root.toFile(), SongDirectoryTraversal.DEFAULT_LIMITS, path -> {
            listings.incrementAndGet();
            return Files.newDirectoryStream(path);
        });

        var result = session.list(root.toFile());
        assertEquals(Set.of("lower.nbs", "UPPER.NBS", "mixed.NbS"), names(result.songs()));
        assertEquals(List.of(nested.toRealPath().toFile()), result.directories());
        assertThrows(UnsupportedOperationException.class, () -> result.songs().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.directories().clear());
        var child = session.list(result.directories().getFirst());
        assertEquals(Set.of("inner.nbs"), names(child.songs()));
        assertTrue(child.directories().isEmpty());
        assertEquals(2, listings.get());
        assertThrows(IOException.class, () -> session.list(root.toFile()));
        assertEquals(2, listings.get(), "a repeated claim must not reopen a directory");
    }

    @Test
    void resultDefensivelyCopiesBothLists() {
        List<File> songs = new ArrayList<>(List.of(new File("one.nbs")));
        List<File> directories = new ArrayList<>(List.of(new File("nested")));
        var result = new SongDirectoryTraversal.Result(songs, directories);
        songs.clear();
        directories.clear();
        assertEquals(1, result.songs().size());
        assertEquals(1, result.directories().size());
    }

    @Test
    void depthStartsAtZeroAndFailsBeforeListingAnExcessiveChild() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path child = Files.createDirectory(root.resolve("child"));
        Files.createDirectory(child.resolve("too-deep"));
        AtomicInteger listings = new AtomicInteger();
        var session = SongDirectoryTraversal.open(root.toFile(), new SongDirectoryTraversal.Limits(1, 20, 20), path -> {
            listings.incrementAndGet();
            return Files.newDirectoryStream(path);
        });
        assertEquals(List.of(child.toRealPath().toFile()), session.list(root.toFile()).directories());
        IOException error = assertThrows(IOException.class, () -> session.list(child.toFile()));
        assertTrue(error.getMessage().contains("depth limit 1"));
        assertEquals(2, listings.get());
    }

    @Test
    void zeroDepthAllowsRootSongsButRejectsAnyChildDirectory() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.write(root.resolve("one.nbs"), new byte[0]);
        var limits = new SongDirectoryTraversal.Limits(0, 1, 1);
        assertEquals(1, SongDirectoryTraversal.open(root.toFile(), limits).list(root.toFile()).songs().size());
        Files.createDirectory(root.resolve("child"));
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile(), limits).list(root.toFile()));
    }

    @Test
    void directoryLimitIncludesRootAndCountsAllDiscoveredBranches() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.createDirectory(root.resolve("a"));
        Files.createDirectory(root.resolve("b"));
        var session = SongDirectoryTraversal.open(root.toFile(), new SongDirectoryTraversal.Limits(10, 2, 10));
        IOException error = assertThrows(IOException.class, () -> session.list(root.toFile()));
        assertTrue(error.getMessage().contains("Directory count limit 2"));
        assertThrows(IOException.class, () -> session.list(root.toFile()), "failure cannot become an empty result");
    }

    @Test
    void songLimitIsSharedAcrossDirectoriesAndAllowsTheExactBoundary() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path child = Files.createDirectory(root.resolve("child"));
        Files.write(root.resolve("a.nbs"), new byte[0]);
        Files.write(child.resolve("b.nbs"), new byte[0]);
        var exact = SongDirectoryTraversal.open(root.toFile(), new SongDirectoryTraversal.Limits(1, 2, 2));
        assertEquals(1, exact.list(root.toFile()).songs().size());
        assertEquals(1, exact.list(child.toFile()).songs().size());

        var limited = SongDirectoryTraversal.open(root.toFile(), new SongDirectoryTraversal.Limits(1, 2, 1));
        limited.list(root.toFile());
        IOException error = assertThrows(IOException.class, () -> limited.list(child.toFile()));
        assertTrue(error.getMessage().contains("Song count limit 1"));
    }

    @Test
    void validatesLimitsAndMissingRoots() {
        assertThrows(IllegalArgumentException.class, () -> new SongDirectoryTraversal.Limits(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SongDirectoryTraversal.Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SongDirectoryTraversal.Limits(1, 1, -1));
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(temporary.resolve("missing").toFile()));
    }

    @Test
    void concurrentClaimsShareTheVisitedSetAndNeverOpenTwice() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        AtomicInteger listings = new AtomicInteger();
        var session = SongDirectoryTraversal.open(root.toFile(), SongDirectoryTraversal.DEFAULT_LIMITS, path -> {
            listings.incrementAndGet();
            return Files.newDirectoryStream(path);
        });
        Callable<Boolean> claim = () -> {
            try {
                session.list(root.toFile());
                return true;
            } catch (IOException expected) {
                return false;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(claim, claim));
            assertEquals(1, (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0));
        }
        assertEquals(1, listings.get());
    }

    @Test
    void listingOpenFailureIsPropagatedAndNotRetried() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        AtomicInteger listings = new AtomicInteger();
        var session = SongDirectoryTraversal.open(root.toFile(), SongDirectoryTraversal.DEFAULT_LIMITS, path -> {
            listings.incrementAndGet();
            throw new IOException("open denied");
        });
        assertTrue(assertThrows(IOException.class, () -> session.list(root.toFile())).getMessage().contains("open denied"));
        assertThrows(IOException.class, () -> session.list(root.toFile()));
        assertEquals(1, listings.get());
    }

    @Test
    void iterationFailureClosesTheStreamAndPropagates() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        AtomicInteger closes = new AtomicInteger();
        var session = SongDirectoryTraversal.open(root.toFile(), SongDirectoryTraversal.DEFAULT_LIMITS, path ->
                new DirectoryStream<>() {
                    @Override
                    public Iterator<Path> iterator() {
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                throw new DirectoryIteratorException(new IOException("iteration denied"));
                            }

                            @Override
                            public Path next() {
                                throw new AssertionError("unreachable");
                            }
                        };
                    }

                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                });
        IOException error = assertThrows(IOException.class, () -> session.list(root.toFile()));
        assertTrue(error.getCause().getMessage().contains("iteration denied"));
        assertEquals(1, closes.get());
        assertThrows(IOException.class, () -> session.list(root.toFile()));
    }

    @Test
    void listingCloseFailureIsNotAnEmptySuccessfulResult() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        var session = SongDirectoryTraversal.open(root.toFile(), SongDirectoryTraversal.DEFAULT_LIMITS, path ->
                new DirectoryStream<>() {
                    @Override
                    public Iterator<Path> iterator() {
                        return List.<Path>of().iterator();
                    }

                    @Override
                    public void close() throws IOException {
                        throw new IOException("close denied");
                    }
                });
        assertTrue(assertThrows(IOException.class, () -> session.list(root.toFile())).getMessage().contains("close denied"));
        assertThrows(IOException.class, () -> session.list(root.toFile()));
    }

    @Test
    void undiscoveredDirectoryCannotBypassTheRootWalk() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path child = Files.createDirectory(root.resolve("child"));
        var session = SongDirectoryTraversal.open(root.toFile());
        assertThrows(IOException.class, () -> session.list(child.toFile()));
    }

    @Test
    void directorySymlinkCyclesAreRejectedWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        createSymbolicLinkOrSkip(root.resolve("cycle"), root);
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
    }

    @Test
    void directorySymlinkToAnotherInternalDirectoryIsAlsoRejectedWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path child = Files.createDirectory(root.resolve("child"));
        createSymbolicLinkOrSkip(root.resolve("alias"), child);
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
    }

    @Test
    void escapingDirectorySymlinkIsRejectedWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        createSymbolicLinkOrSkip(root.resolve("escape"), outside);
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
    }

    @Test
    void rootAliasMayResolveToADifferentRealPathWhenSupported() throws Exception {
        Path realRoot = Files.createDirectory(temporary.resolve("real-root"));
        Path child = Files.createDirectory(realRoot.resolve("child"));
        Files.write(child.resolve("song.nbs"), new byte[0]);
        Path alias = temporary.resolve("root-alias");
        createSymbolicLinkOrSkip(alias, realRoot);
        var session = SongDirectoryTraversal.open(alias.toFile());
        assertEquals(realRoot.toRealPath().toFile(), session.root());
        var result = session.list(alias.toFile());
        assertEquals(List.of(child.toRealPath().toFile()), result.directories());
        assertEquals(1, session.list(result.directories().getFirst()).songs().size());
    }

    @Test
    void changingRootAliasCannotRedirectAnExistingSessionWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path other = Files.createDirectory(temporary.resolve("other"));
        Path alias = temporary.resolve("alias");
        createSymbolicLinkOrSkip(alias, root);
        var session = SongDirectoryTraversal.open(alias.toFile());
        Files.delete(alias);
        createSymbolicLinkOrSkip(alias, other);
        assertThrows(IOException.class, () -> session.list(alias.toFile()));
    }

    @Test
    void fileSymlinkIsAllowedOnlyInsideRootWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path song = Files.write(root.resolve("source.nbs"), new byte[0]);
        createSymbolicLinkOrSkip(root.resolve("linked.NBS"), song);
        assertEquals(2, SongDirectoryTraversal.open(root.toFile()).list(root.toFile()).songs().size());
        Path outside = Files.write(temporary.resolve("outside.nbs"), new byte[0]);
        createSymbolicLinkOrSkip(root.resolve("escape.nbs"), outside);
        IOException error = assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
        assertTrue(error.getMessage().contains("escapes root"));
    }

    @Test
    void brokenSymlinkIsAnErrorWhenSupported() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        createSymbolicLinkOrSkip(root.resolve("broken.nbs"), root.resolve("missing.nbs"));
        assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionCycleIsRejectedWhenCreationIsAvailable() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path junction = root.resolve("cycle");
        Process process;
        try {
            process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    junction.toString(), root.toString()).redirectErrorStream(true).start();
        } catch (IOException | SecurityException unsupported) {
            assumeTrue(false, "Cannot create test junction: " + unsupported.getMessage());
            return;
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assumeTrue(process.waitFor() == 0, "Cannot create test junction: " + output);
        try {
            assertThrows(IOException.class, () -> SongDirectoryTraversal.open(root.toFile()).list(root.toFile()));
        } finally {
            Files.deleteIfExists(junction);
        }
        assertFalse(Files.exists(junction));
    }

    private static Set<String> names(List<File> files) {
        return files.stream().map(File::getName).collect(Collectors.toSet());
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "Symbolic links unavailable: " + unsupported.getMessage());
        }
    }
}
