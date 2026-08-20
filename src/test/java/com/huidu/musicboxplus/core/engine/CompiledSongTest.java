package com.huidu.musicboxplus.core.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huidu.musicboxplus.core.nbs.NbsCorpus;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// Compiling a song rearranges every note, so the check that matters is that nothing is lost,
// reordered or altered in the process. Each corpus song is compared note by note against its
// parsed form.
class CompiledSongTest {

    private static final int DEFAULT_LAYER_VOLUME = 100;
    private static final int DEFAULT_LAYER_PANNING = 100;

    private static List<Path> bundled() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            return stream.filter(p -> p.toString().endsWith(".nbs")).sorted().toList();
        }
    }

    // Notes are grouped by tick while preserving their order within a tick, and every field
    // survives the move.
    private static void checkRoundTrip(List<String> failures, String name, RawNbsSong raw) {
        CompiledSong song = CompiledSong.compile(raw);

        int layerCount = raw.layers().size();
        List<List<RawNbsNote>> byTick = new ArrayList<>();
        for (int t = 0; t < song.tickCount(); t++) {
            byTick.add(new ArrayList<>());
        }
        int dropped = 0;
        for (RawNbsNote note : raw.notes()) {
            if (note.tick() >= 0 && note.tick() < song.tickCount()) {
                byTick.get(note.tick()).add(note);
            } else {
                dropped++;
            }
        }
        if (dropped != 0) {
            failures.add(name + ": " + dropped + " notes fell outside the compiled tick range");
        }
        if (song.totalNotes() != raw.notes().size() - dropped) {
            failures.add(name + ": kept " + song.totalNotes() + " of " + raw.notes().size() + " notes");
            return;
        }

        for (int t = 0; t < song.tickCount(); t++) {
            List<RawNbsNote> expected = byTick.get(t);
            int start = song.noteStart(t);
            int end = song.noteEnd(t);
            if (end - start != expected.size()) {
                failures.add(name + " tick " + t + ": span holds " + (end - start)
                        + " notes, source has " + expected.size());
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                RawNbsNote note = expected.get(i);
                int at = start + i;
                if (song.instrument(at) != note.instrument() || song.key(at) != note.key()
                        || song.velocity(at) != note.velocity() || song.panning(at) != note.panning()
                        || song.finePitch(at) != note.finePitch()) {
                    failures.add(name + " tick " + t + " note " + i + ": fields differ");
                    return;
                }
                int layer = note.layer();
                int wantVolume = layer >= 0 && layer < layerCount
                        ? raw.layers().get(layer).volume() : DEFAULT_LAYER_VOLUME;
                int wantPanning = layer >= 0 && layer < layerCount
                        ? raw.layers().get(layer).panning() : DEFAULT_LAYER_PANNING;
                if (song.layerVolume(at) != wantVolume || song.layerPanning(at) != wantPanning) {
                    failures.add(name + " tick " + t + " note " + i + ": layer fields differ, "
                            + "got " + song.layerVolume(at) + "/" + song.layerPanning(at)
                            + " want " + wantVolume + "/" + wantPanning);
                    return;
                }
            }
        }
    }

    private static void checkLayout(List<String> failures, String name, CompiledSong song) {
        if (song.noteStart(0) != 0) {
            failures.add(name + ": first tick does not start at 0");
        }
        int previous = 0;
        for (int t = 0; t < song.tickCount(); t++) {
            int start = song.noteStart(t);
            int end = song.noteEnd(t);
            if (start != previous) {
                failures.add(name + " tick " + t + ": span starts at " + start + ", previous ended at " + previous);
                return;
            }
            if (end < start) {
                failures.add(name + " tick " + t + ": span ends before it starts");
                return;
            }
            previous = end;
        }
        if (previous != song.totalNotes()) {
            failures.add(name + ": spans cover " + previous + " of " + song.totalNotes() + " notes");
        }
    }

    @Test
    void bundledCorpusCompilesWithoutLosingAnything() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Path file : bundled()) {
            RawNbsSong raw = NbsReader.read(file);
            checkRoundTrip(failures, file.getFileName().toString(), raw);
            checkLayout(failures, file.getFileName().toString(), CompiledSong.compile(raw));
        }
        assertFalse(bundled().isEmpty(), "corpus is empty");
        assertEquals(List.of(), failures);
    }

    @Test
    void extendedCorpusCompilesWithoutLosingAnything() throws Exception {
        Path root = NbsCorpus.extendedRoot();
        assumeTrue(root != null, "extended corpus not found");

        List<String> failures = new ArrayList<>();
        long notes = 0;
        long bytes = 0;
        int layerOverflows = 0;
        for (Path rel : NbsCorpus.collect(root)) {
            RawNbsSong raw = NbsReader.read(root.resolve(rel));
            CompiledSong song = CompiledSong.compile(raw);
            checkRoundTrip(failures, rel.toString(), raw);
            checkLayout(failures, rel.toString(), song);
            notes += song.totalNotes();
            bytes += song.approximateBytes();
            int layerCount = raw.layers().size();
            for (RawNbsNote note : raw.notes()) {
                if (note.layer() >= layerCount) {
                    layerOverflows++;
                    break;
                }
            }
            if (failures.size() > 10) {
                break;
            }
        }
        assertEquals(List.of(), failures);
        assertTrue(notes > 1_000_000, "expected the extended corpus to hold over a million notes, got " + notes);
        assertTrue(layerOverflows > 0,
                "expected at least one song naming a layer past its own layer table, since that "
                        + "is the case the compile-time defaulting exists for");
        // The arrangement the engine replaces needed roughly 143 MB for this corpus once the
        // song object graph it pinned is counted. Well clear of that is the whole point.
        assertTrue(bytes < 40L * 1024 * 1024,
                "compiled corpus grew to " + (bytes / 1024 / 1024) + " MB, which defeats the "
                        + "reason for the primitive-array layout");
    }

    // A note may name a layer index at or past songHeight, which has no entry in the layer
    // table. Those must fall back to neutral values rather than being looked up.
    @Test
    void notesBeyondTheLayerTableUseNeutralDefaults() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path file : bundled()) {
            RawNbsSong raw = NbsReader.read(file);
            int layerCount = raw.layers().size();
            CompiledSong song = CompiledSong.compile(raw);
            for (RawNbsNote note : raw.notes()) {
                if (note.layer() >= layerCount) {
                    found.add(file.getFileName() + " layer=" + note.layer() + " of " + layerCount);
                    break;
                }
            }
            // Whether or not such a note exists here, nothing may read past the table.
            for (int i = 0; i < song.totalNotes(); i++) {
                assertTrue(song.layerVolume(i) >= 0 && song.layerVolume(i) <= 127,
                        file.getFileName() + ": layer volume out of range at note " + i);
                assertTrue(song.layerPanning(i) >= 0 && song.layerPanning(i) <= 200,
                        file.getFileName() + ": layer panning out of range at note " + i);
            }
        }
        // Reported rather than asserted: the defaulting has to be correct either way, but it
        // is worth knowing whether real songs exercise it.
        System.out.println("notes naming a layer past the table: " + found);
    }

    @Test
    void speedIsNotPartOfTheArrangement() throws Exception {
        RawNbsSong raw = NbsReader.read(bundled().get(0));
        CompiledSong a = CompiledSong.compile(raw);
        CompiledSong b = CompiledSong.compile(raw);
        assertEquals(a.tickCount(), b.tickCount());
        assertEquals(a.totalNotes(), b.totalNotes());
        assertEquals(a.ticksPerSecond(), b.ticksPerSecond());
        // Nothing in the compiled form names a speed multiplier, so one arrangement serves
        // every playback speed and no per-speed duplicate is ever built.
        for (var method : CompiledSong.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("multiplier"), "speed must stay on the playback cursor, not in the arrangement");
        }
    }

    @Test
    void stereoIsPrecomputed() throws Exception {
        for (Path file : bundled()) {
            RawNbsSong raw = NbsReader.read(file);
            CompiledSong song = CompiledSong.compile(raw);
            boolean expected = false;
            int layerCount = raw.layers().size();
            for (RawNbsNote note : raw.notes()) {
                int layerPan = note.layer() >= 0 && note.layer() < layerCount
                        ? raw.layers().get(note.layer()).panning() : DEFAULT_LAYER_PANNING;
                if (note.panning() != 100 || layerPan != 100) {
                    expected = true;
                    break;
                }
            }
            assertEquals(expected, song.isStereo(), file.getFileName().toString());
        }
    }

    @Test
    void enginePackageHasNoBukkitDependency() throws Exception {
        Path dir = Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "engine");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(x -> x.toString().endsWith(".java")).toList()) {
                String code = Files.readString(p, StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                if (code.contains("org.bukkit")) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "core.engine must stay free of org.bukkit");
    }
}
