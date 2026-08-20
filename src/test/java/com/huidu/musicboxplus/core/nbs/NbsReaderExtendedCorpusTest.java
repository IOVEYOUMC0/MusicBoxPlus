package com.huidu.musicboxplus.core.nbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huidu.musicboxplus.core.sound.NotePitch;
import com.huidu.musicboxplus.core.sound.SongInstruments;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

// The same cross-check over the large out-of-repo corpus, which reaches versions the
// bundled 24 files do not.
//
// That gap has cost something already: with no v1 or v2 file in the bundled corpus,
// reading a song length field that only exists from v3 onwards went unnoticed in both
// implementations at once. Two readers written by the same hand share the same
// misreadings; only wider input catches those.
//
// The corpus lives outside the repository, so these skip when it is absent.
class NbsReaderExtendedCorpusTest {

    private static final Path REFERENCE =
            Path.of("src", "test", "resources", "nbs-reference-extended.txt");

    @Test
    void everyExtendedCorpusFileMatchesTheIndependentReader() throws Exception {
        Path root = NbsCorpus.extendedRoot();
        assumeTrue(root != null, "extended corpus not found");
        assumeTrue(Files.exists(REFERENCE), "missing baseline " + REFERENCE);

        Map<String, NbsReference.Expected> reference = NbsReference.load(REFERENCE);
        assertTrue(reference.size() > 100, "baseline has too few entries: " + reference.size());

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, NbsReference.Expected> e : reference.entrySet()) {
            String name = e.getKey();
            if (e.getValue() == null) {
                mismatches.add(name + ": reference reader failed to parse it");
                continue;
            }
            Path file = root.resolve(name);
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
                        + String.join("\n  ", mismatches.subList(0, Math.min(30, mismatches.size()))));
    }

    // Without a v1 or v2 file present the version-gated length branch is not exercised at
    // all, which is exactly how it stayed broken.
    @Test
    void extendedCorpusCoversTheVersionsBundledCorpusMisses() throws Exception {
        Path root = NbsCorpus.extendedRoot();
        assumeTrue(root != null, "extended corpus not found");

        Map<Integer, Integer> byVersion = new TreeMap<>();
        for (Path rel : NbsCorpus.collect(root)) {
            byVersion.merge(NbsReader.read(root.resolve(rel)).version(), 1, Integer::sum);
        }
        assertTrue(byVersion.containsKey(1) || byVersion.containsKey(2),
                "no v1/v2 file in the extended corpus, length branch uncovered. Found: " + byVersion);
    }

    @Test
    void everyExtendedCorpusNoteIsPlayable() throws Exception {
        Path root = NbsCorpus.extendedRoot();
        assumeTrue(root != null, "extended corpus not found");

        List<String> failures = new ArrayList<>();
        long noteCount = 0;
        for (Path rel : NbsCorpus.collect(root)) {
            RawNbsSong song = NbsReader.read(root.resolve(rel));
            SongInstruments instruments = SongInstruments.of(song);
            for (RawNbsNote note : song.notes()) {
                noteCount++;
                if (failures.size() > 30) {
                    break;
                }
                float transposed = NotePitch.transposedPitch(note.key(), note.finePitch());
                float bucketed = NotePitch.bucketPitch(note.key(), note.finePitch());
                int bucket = NotePitch.bucketIndex(note.key(), note.finePitch());
                if (outsidePlayableRange(transposed) || outsidePlayableRange(bucketed)) {
                    failures.add(rel + " key=" + note.key() + " fine=" + note.finePitch()
                            + " pitch out of range: transposed=" + transposed + " bucket=" + bucketed);
                }
                if (bucket < 0 || bucket > 4) {
                    failures.add(rel + " bucket out of range: " + bucket);
                }
                String name = instruments.soundName(note.instrument(), bucket);
                if (name.isEmpty() && !instruments.isSilent(note.instrument())) {
                    failures.add(rel + " instrument=" + note.instrument()
                            + " resolved to an empty name without being silent");
                }
            }
        }

        assertTrue(noteCount > 100_000, "too few notes in the extended corpus: " + noteCount);
        assertEquals(List.of(), failures, "some notes in the extended corpus are unplayable");
    }

    // playSound accepts [0.5, 2.0]; anything else would be rejected or clamped by the server.
    private static boolean outsidePlayableRange(float pitch) {
        return !(pitch >= 0.5f) || !(pitch <= 2.0f);
    }
}
