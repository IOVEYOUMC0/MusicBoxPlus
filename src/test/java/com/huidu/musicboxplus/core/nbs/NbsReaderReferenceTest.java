package com.huidu.musicboxplus.core.nbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Cross-check against the independent reference reader over the bundled corpus.
// See NbsReference for what the fingerprint covers and why.
class NbsReaderReferenceTest {

    private static final Path REFERENCE = Path.of("src", "test", "resources", "nbs-reference.txt");

    @Test
    void referenceAndCorpusArePresent() throws Exception {
        assertTrue(Files.exists(REFERENCE), "missing baseline " + REFERENCE);
        assertTrue(Files.isDirectory(NbsCorpus.BUNDLED), "missing corpus " + NbsCorpus.BUNDLED);
        assertFalse(NbsReference.load(REFERENCE).isEmpty(), "baseline has no entries");
    }

    @Test
    void everyCorpusFileMatchesTheIndependentReader() throws Exception {
        Map<String, NbsReference.Expected> reference = NbsReference.load(REFERENCE);
        List<String> mismatches = new ArrayList<>();

        for (Map.Entry<String, NbsReference.Expected> e : reference.entrySet()) {
            String name = e.getKey();
            if (e.getValue() == null) {
                mismatches.add(name + ": reference reader failed to parse it");
                continue;
            }
            Path file = NbsCorpus.BUNDLED.resolve(name);
            if (!Files.exists(file)) {
                mismatches.add(name + ": missing from corpus");
                continue;
            }
            try {
                mismatches.addAll(NbsReference.compare(name, e.getValue(), NbsReader.read(file)));
            } catch (Throwable t) {
                mismatches.add(name + ": threw " + t);
            }
        }

        assertTrue(mismatches.isEmpty(),
                "reader disagrees with the independent reference (" + mismatches.size() + "):\n  "
                        + String.join("\n  ", mismatches));
    }

    // Parsing a .nbs is I/O and arithmetic. Depending on a running server would put the
    // whole format layer beyond the reach of ordinary unit tests.
    @Test
    void parserPackageHasNoBukkitDependency() throws Exception {
        Path dir = Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "nbs");
        List<String> offenders = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).toList()) {
                // Strip comments first, or the rule's own description would trip it.
                String code = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                if (code.contains("org.bukkit")) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "core.nbs must stay free of org.bukkit");
    }
}
