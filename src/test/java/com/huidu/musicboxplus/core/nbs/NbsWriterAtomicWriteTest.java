package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// NbsWriter.write(song, Path) used to open the target first and validate while writing, so a song
// that failed a range check left an empty or half-written file where the player's only valid export
// used to be. The target must survive a failed write untouched.
class NbsWriterAtomicWriteTest {

    @TempDir
    Path directory;

    private static RawNbsSong songWithNoteAtTick(int tick) {
        return new RawNbsSong(4, 20, 8, 2, "t", "a", "o", "d", 1234, 3,
                false, 0, 0,
                List.of(new RawNbsNote(tick, 0, 0, 45, 100, 100, 0)),
                List.of(),
                List.of());
    }

    @Test
    void failedWriteLeavesThePreviousExportUntouched() throws IOException {
        Path target = directory.resolve("song.nbs");
        byte[] previous = "previous export".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(target, previous);

        // Tick 70000 is outside the format's unsigned-short range, so the writer rejects it.
        assertThrows(IOException.class, () -> NbsWriter.write(songWithNoteAtTick(70000), target));

        assertEquals("previous export", Files.readString(target));
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(target.getFileName()),
                    entries.map(path -> path.getFileName()).toList(),
                    "the temporary file must be cleaned up");
        }
    }

    @Test
    void successfulWriteReplacesThePreviousExport() throws IOException {
        Path target = directory.resolve("song.nbs");
        Files.writeString(target, "previous export");

        NbsWriter.write(songWithNoteAtTick(4), target);

        RawNbsSong reread = NbsReader.read(target);
        assertEquals(1, reread.notes().size());
        assertEquals(4, reread.notes().get(0).tick());
    }

    // 65535 - (-1) is a 65536-tick gap, one more than a single 16-bit jump can express. The writer
    // emits an empty run in between instead of rejecting the song.
    @Test
    void noteAtTheLastTickRoundTrips() throws IOException {
        Path target = directory.resolve("last-tick.nbs");

        NbsWriter.write(songWithNoteAtTick(0xFFFF), target);

        RawNbsSong reread = NbsReader.read(target);
        assertEquals(1, reread.notes().size());
        assertEquals(0xFFFF, reread.notes().get(0).tick());
    }

    // pitch 0 is a legitimate sample key; only a negative value means "use the default". The writer
    // used to turn a file's key 0 into 45 on the way out.
    @Test
    void customInstrumentPitchZeroSurvivesTheRoundTrip() throws IOException {
        Path target = directory.resolve("pitch-zero.nbs");
        RawNbsSong song = new RawNbsSong(4, 20, 8, 2, "t", "a", "o", "d", 1234, 3,
                false, 0, 0,
                List.of(),
                List.of(),
                List.of(new RawNbsCustomInstrument("low", "low.ogg", 0, false)));

        NbsWriter.write(song, target);

        assertEquals(0, NbsReader.read(target).customInstruments().get(0).pitch());
    }
}
