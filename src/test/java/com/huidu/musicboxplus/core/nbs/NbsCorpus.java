package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// Locates the NBS test corpora.
//
// The bundled corpus is 24 files with very narrow version coverage - no v1 or v2 at all - so
// format quirks that only exist in some header versions can pass unnoticed. An optional larger
// corpus outside the repository (hundreds of real files spanning v0 to v5) can be pointed at
// instead, via system property or environment variable, in that order.
//
// That corpus is an enhancement rather than a prerequisite, so tests skip when it is missing
// instead of failing.
public final class NbsCorpus {

    // Bundled corpus. It lives under Reference/, which is git-ignored, so it exists on a developer
    // machine but not in a fresh clone; use bundled() rather than this field when a test needs it.
    public static final Path BUNDLED =
            Path.of("Reference", "boombox", "decompiled", "resources", "songs");

    private static final String PROPERTY = "musicboxplus.test.corpus.extended";
    private static final String ENV = "MUSICBOX_TEST_CORPUS";

    private NbsCorpus() {
    }

    public static Path extendedRoot() {
        for (String candidate : new String[]{
                System.getProperty(PROPERTY), System.getenv(ENV)}) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Path.of(candidate);
                if (Files.isDirectory(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    // The corpus is a local asset: Reference/ is git-ignored, so a fresh clone (and therefore CI)
    // does not have it. Tests that need real files must call this instead of reading BUNDLED
    // directly; it skips the test with an explanation rather than failing on a missing directory.
    // Skipping is a real loss of coverage, not a pass: run the suite locally with the corpus present
    // before trusting format-related changes.
    public static Path bundled() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(BUNDLED),
                "Bundled NBS corpus is absent (Reference/ is not in the repository); "
                        + "set it up locally to run this test: " + BUNDLED.toAbsolutePath());
        return BUNDLED;
    }

    // Collects .nbs files recursively as root-relative paths, in a stable order.
    public static List<Path> collect(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                    .map(root::relativize)
                    .sorted()
                    .toList();
        }
    }
}
