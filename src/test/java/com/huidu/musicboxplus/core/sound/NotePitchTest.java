package com.huidu.musicboxplus.core.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// Differential test of this pitch conversion against the one it replaced.
//
// The goal is not to be identical to the old implementation but to pin down the relationship:
// value-for-value equal over the domain real notes actually cover, and an explicit record of
// what this implementation returns at the edges where the old one crashes or picks the wrong
// bucket. Without that, swapping the old implementation out could only be checked by ear.
//
// The reference at the bottom is a transcription of NoteBlockAPI's NoteUtils and
// InstrumentUtils, kept here rather than called through the library: the comparison has to
// outlive the dependency, and the library's own classes cannot be loaded in a test JVM anyway
// (they reach Bukkit.getServer() during class initialisation). Byte and short arithmetic is
// reproduced exactly, wrap-around included, since that is what produces the edge cases below.
class NotePitchTest {

    private static final Path CORPUS = Path.of("Reference", "boombox", "decompiled", "resources", "songs");

    // Range playSound accepts.
    private static final float MIN_PITCH = 0.5f;
    private static final float MAX_PITCH = 2.0f;

    @Test
    void tableSpansExactlyTwoOctaves() {
        assertEquals(MIN_PITCH, NotePitch.transposedPitch(33, 0), "3300 音分应为下界 0.5");
        assertEquals(1.0f, NotePitch.transposedPitch(45, 0), "4500 音分应为中心 1.0");
        assertEquals(MAX_PITCH, NotePitch.transposedPitch(57, 0), "5700 音分应为上界 2.0");
    }

    // Transpose mode must match the old implementation value for value across every note a short can express.
    @Test
    void transposedPitchMatchesNoteBlockApi() {
        List<String> diffs = new ArrayList<>();
        for (int key = 0; key <= 87; key++) {
            for (int fine = -1200; fine <= 1200; fine += 7) {
                float mine = NotePitch.transposedPitch(key, fine);
                float theirs = NoteBlockApiReference.getPitchTransposed((byte) key, (short) fine);
                if (mine != theirs && diffs.size() < 10) {
                    diffs.add("key=" + key + " fine=" + fine + " 自研=" + mine + " NBA=" + theirs);
                }
            }
        }
        assertEquals(List.of(), diffs, "转调模式必须与 NoteBlockAPI 完全一致");
    }

    // Bucketing matches the old implementation over the domain real notes cover, i.e. non-negative
    // finePitch. A negative finePitch makes the old implementation compute a negative array index;
    // see negativeFinePitchIsSafe.
    @Test
    void bucketMatchesNoteBlockApiWhereNoteBlockApiIsWellDefined() {
        List<String> diffs = new ArrayList<>();
        for (int key = 0; key < 105; key++) {
            for (int fine = 0; fine <= 99; fine++) {
                String mine = NotePitch.bucketSuffix(NotePitch.bucketIndex(key, fine));
                String theirs = NoteBlockApiReference.warpNameOutOfRange("x", (byte) key, (short) fine).substring(1);
                if (!mine.equals(theirs) && diffs.size() < 10) {
                    diffs.add("key=" + key + " fine=" + fine + " 自研后缀='" + mine + "' NBA后缀='" + theirs + "'");
                }
                float minePitch = NotePitch.bucketPitch(key, fine);
                float theirsPitch = NoteBlockApiReference.getPitchInOctave((byte) key, (short) fine);
                if (minePitch != theirsPitch && diffs.size() < 10) {
                    diffs.add("key=" + key + " fine=" + fine + " 自研=" + minePitch + " NBA=" + theirsPitch);
                }
            }
        }
        assertEquals(List.of(), diffs, "分档模式在 finePitch 非负时必须与 NoteBlockAPI 一致");
    }

    // Negative finePitch right on a bucket boundary: the old implementation subtracts the bucket
    // base to get 0, adds the negative offset and indexes out of bounds with an
    // ArrayIndexOutOfBoundsException. This implementation locates notes by total cents, so they
    // land at the top end of the bucket below.
    @Test
    void negativeFinePitchIsSafe() {
        int[] boundaries = {9, 33, 57, 81};
        for (int key : boundaries) {
            for (int fine = -1; fine >= -99; fine--) {
                float pitch = NotePitch.bucketPitch(key, fine);
                assertTrue(pitch >= MIN_PITCH && pitch <= MAX_PITCH,
                        "key=" + key + " fine=" + fine + " 的 pitch 越界：" + pitch);
                // Below the bucket base, so it belongs to the bucket below, not this one.
                assertEquals(NotePitch.bucketIndex(key, 0) - 1, NotePitch.bucketIndex(key, fine),
                        "key=" + key + " fine=" + fine + " 应落在低一档");
            }
        }
    }

    // For every note in the corpus, both modes must return a value playSound accepts and must not
    // throw. This is the criterion for whether this layer can replace the old implementation.
    @Test
    void everyCorpusNoteProducesAPlayablePitch() throws Exception {
        assertTrue(Files.isDirectory(CORPUS), "缺少语料目录 " + CORPUS);
        List<String> failures = new ArrayList<>();
        int noteCount = 0;

        List<Path> files;
        try (Stream<Path> stream = Files.list(CORPUS)) {
            files = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().toList();
        }

        for (Path file : files) {
            RawNbsSong song = NbsReader.read(file);
            for (RawNbsNote note : song.notes()) {
                noteCount++;
                check(failures, file, note, "transposed",
                        () -> NotePitch.transposedPitch(note.key(), note.finePitch()));
                check(failures, file, note, "bucket",
                        () -> NotePitch.bucketPitch(note.key(), note.finePitch()));
                int bucket = NotePitch.bucketIndex(note.key(), note.finePitch());
                if (bucket < 0 || bucket > 4) {
                    failures.add(file.getFileName() + " " + describe(note) + " 档位越界：" + bucket);
                }
            }
        }

        assertTrue(noteCount > 0, "语料里没有音符");
        assertTrue(failures.isEmpty(),
                "共 " + noteCount + " 个音符中有 " + failures.size() + " 个不可播：\n  "
                        + String.join("\n  ", failures.subList(0, Math.min(20, failures.size()))));
    }

    private interface PitchSupplier {
        float get();
    }

    private static void check(List<String> failures, Path file, RawNbsNote note,
                              String mode, PitchSupplier supplier) {
        float pitch;
        try {
            pitch = supplier.get();
        } catch (RuntimeException e) {
            failures.add(file.getFileName() + " " + describe(note) + " " + mode + " 抛异常 " + e);
            return;
        }
        if (!(pitch >= MIN_PITCH && pitch <= MAX_PITCH)) {
            failures.add(file.getFileName() + " " + describe(note) + " " + mode + " 越界 " + pitch);
        }
    }

    private static String describe(RawNbsNote note) {
        return "key=" + note.key() + " fine=" + note.finePitch();
    }

    // Transcription of NoteBlockAPI's pitch conversion, for comparison only.
    private static final class NoteBlockApiReference {

        private static final float[] PITCHES = new float[2401];

        static {
            for (int i = 0; i < PITCHES.length; i++) {
                PITCHES[i] = (float) Math.pow(2, (i - 1200d) / 1200d);
            }
        }

        private NoteBlockApiReference() {
        }

        static float getPitchTransposed(byte key, short pitch) {
            pitch += (short) (key * 100);
            while (pitch < 3300) {
                pitch += 1200;
            }
            while (pitch > 5700) {
                pitch -= 1200;
            }
            pitch -= 3300;
            return PITCHES[pitch];
        }

        static float getPitchInOctave(byte key, short pitch) {
            key = applyPitchToKey(key, pitch);
            pitch %= 100;

            // Bucket bases: -15 / 9 / 33 / 57 / 81, 24 keys apart.
            if (key < 9) {
                key -= -15;
            } else if (key < 33) {
                key -= 9;
            } else if (key < 57) {
                key -= 33;
            } else if (key < 81) {
                key -= 57;
            } else if (key < 105) {
                key -= 81;
            }

            return PITCHES[key * 100 + pitch];
        }

        static String warpNameOutOfRange(String name, byte key, short pitch) {
            key = applyPitchToKey(key, pitch);
            if (key < 9) {
                return name + "_-2";
            } else if (key < 33) {
                return name + "_-1";
            } else if (key < 57) {
                return name;
            } else if (key < 81) {
                return name + "_1";
            } else if (key < 105) {
                return name + "_2";
            }
            return name;
        }

        private static byte applyPitchToKey(byte key, short pitch) {
            key += (byte) (pitch / 100);
            return key;
        }
    }
}
