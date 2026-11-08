package com.huidu.musicboxplus.core.nbs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbsWriterValidationTest {

    @TempDir
    Path directory;

    @Test
    void rejectsFinePitchOutsideSignedShortBeforeWritingAnything() throws IOException {
        for (int pitch : new int[]{Short.MIN_VALUE - 1, Short.MAX_VALUE + 1,
                Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertRejected(song(List.of(new RawNbsNote(0, 0, 0, 45, 100, 100, pitch)),
                    List.of(), List.of()), "note fine pitch");
        }
    }

    @Test
    void rejectsEveryOutOfRangeNoteByteInsteadOfClampingOrWrapping() throws IOException {
        String[] fields = {"note instrument", "note key", "note velocity", "note panning"};
        for (int field = 0; field < fields.length; field++) {
            for (int invalid : new int[]{-1, 256, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                int[] values = {0, 45, 100, 100};
                values[field] = invalid;
                RawNbsNote note = new RawNbsNote(0, 0, values[0], values[1], values[2], values[3], 0);
                assertRejected(song(List.of(note), List.of(), List.of()), fields[field]);
            }
        }
    }

    @Test
    void rejectsHeaderValuesInsteadOfClampingOrNormalizingInvalidInput() throws IOException {
        String[] fields = {"vanilla instrument count", "song length", "song height", "tempo",
                "time signature", "max loop count", "loop start tick"};
        int[] maxima = {255, 65535, 65535, 65535, 255, 255, 65535};
        for (int field = 0; field < fields.length; field++) {
            for (int invalid : new int[]{-1, maxima[field] + 1}) {
                int[] values = {20, 8, 1, 1000, 4, 0, 0};
                values[field] = invalid;
                RawNbsSong song = new RawNbsSong(4, values[0], values[1], values[2],
                        "t", "a", "o", "d", values[3], values[4], false, values[5], values[6],
                        List.of(), List.of(), List.of());
                assertRejected(song, fields[field]);
            }
        }
    }

    @Test
    void validatesTrailingLayerAndCustomInstrumentFieldsBeforeTheHeaderIsWritten() throws IOException {
        for (int invalid : new int[]{-1, 256}) {
            assertRejected(song(List.of(), List.of(new RawNbsLayer(0, "", false, invalid, 100)),
                    List.of()), "layer volume");
            assertRejected(song(List.of(), List.of(new RawNbsLayer(0, "", false, 100, invalid)),
                    List.of()), "layer panning");
            assertRejected(song(List.of(), List.of(),
                    List.of(new RawNbsCustomInstrument("", "", invalid, false))), "custom instrument pitch");
        }
        List<RawNbsCustomInstrument> instruments = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            instruments.add(new RawNbsCustomInstrument("", "", 45, false));
        }
        assertRejected(song(List.of(), List.of(), instruments), "custom instrument count");
    }

    @Test
    void rejectsDuplicateSlotsEvenWhenNotAdjacentInInputOrMusicallyDifferent() throws IOException {
        RawNbsNote first = new RawNbsNote(4, 2, 0, 45, 100, 100, 0);
        RawNbsNote different = new RawNbsNote(4, 2, 255, 255, 0, 200, Short.MIN_VALUE);
        RawNbsNote other = new RawNbsNote(0, 0, 1, 46, 50, 0, 1);
        for (RawNbsNote duplicate : List.of(first, different)) {
            assertRejected(song(List.of(first, other, duplicate), List.of(), List.of()),
                    "duplicate note at tick 4, layer 2");
        }
    }

    @Test
    void rejectsUnrepresentableCoordinatesAndDuplicateLayerMetadata() throws IOException {
        for (int tick : new int[]{-1, 65536}) {
            assertRejected(song(List.of(new RawNbsNote(tick, 0, 0, 45, 100, 100, 0)),
                    List.of(), List.of()), "note tick");
        }
        for (int layer : new int[]{-1, 65535}) {
            assertRejected(song(List.of(new RawNbsNote(0, layer, 0, 45, 100, 100, 0)),
                    List.of(), List.of()), "note layer");
            assertRejected(song(List.of(), List.of(new RawNbsLayer(layer, "", false, 100, 100)),
                    List.of()), "layer index");
        }
        assertRejected(song(List.of(), List.of(new RawNbsLayer(0, "a", false, 100, 100),
                new RawNbsLayer(0, "b", true, 0, 0)), List.of()), "duplicate layer index");
    }

    @Test
    void acceptsUnsignedKeyExtensionsAndExactWireBoundariesWithoutLosingNotes() throws IOException {
        List<RawNbsNote> expected = List.of(
                new RawNbsNote(0, 0, 0, 0, 0, 0, Short.MIN_VALUE),
                new RawNbsNote(0, 1, 255, 255, 255, 255, Short.MAX_VALUE),
                // Repeated musical values are legitimate on a different layer or tick.
                new RawNbsNote(0, 2, 255, 255, 255, 255, Short.MAX_VALUE),
                new RawNbsNote(65535, 65534, 255, 255, 255, 255, Short.MAX_VALUE));
        List<RawNbsCustomInstrument> instruments = new ArrayList<>();
        for (int i = 0; i < 255; i++) {
            instruments.add(new RawNbsCustomInstrument("", "", i == 0 ? 0 : 255, i % 2 == 0));
        }
        RawNbsLayer first = new RawNbsLayer(0, "first", false, 0, 0);
        RawNbsLayer last = new RawNbsLayer(65534, "last", true, 255, 255);
        RawNbsSong song = new RawNbsSong(5, 255, 65535, 65535, "t", "a", "o", "d",
                65535, 255, true, 255, 65535,
                List.of(expected.get(3), expected.get(1), expected.get(0), expected.get(2)),
                List.of(last, first), instruments);

        RawNbsSong reread = roundTrip(song);

        assertEquals(4, reread.version(), "do not silently invent a new output format version");
        assertEquals(expected, reread.notes(), "compare all notes, not a set or a deduplicated list");
        assertEquals(first, reread.layers().get(0));
        assertEquals(last, reread.layers().get(65534));
        assertEquals(instruments, reread.customInstruments());
        assertEquals(255, reread.vanillaInstrumentCount());
        assertEquals(65535, reread.lengthTicks());
        assertEquals(65535, reread.songHeight());
        assertEquals(65535, reread.tempoRaw());
        assertEquals(255, reread.timeSignature());
        assertTrue(reread.loopEnabled());
        assertEquals(255, reread.maxLoopCount());
        assertEquals(65535, reread.loopStartTick());
    }

    @Test
    void preservesZeroHeaderBytesAndNormalizesNullableStringsAndLists() throws IOException {
        RawNbsSong song = new RawNbsSong(4, 0, 0, 0, null, null, null, null,
                0, 0, false, 0, 0, null,
                List.of(new RawNbsLayer(0, null, false, 0, 0)),
                List.of(new RawNbsCustomInstrument(null, null, 0, false)));
        RawNbsSong reread = roundTrip(song);
        assertEquals(0, reread.vanillaInstrumentCount());
        assertEquals(0, reread.tempoRaw(), "raw u16 zero must not be clamped to 1");
        assertEquals(0, reread.timeSignature());
        assertEquals("", reread.title());
        assertEquals("", reread.author());
        assertEquals("", reread.originalAuthor());
        assertEquals("", reread.description());
        assertEquals(List.of(), reread.notes());
        assertEquals(List.of(new RawNbsLayer(0, "", false, 0, 0)), reread.layers());
        assertEquals(List.of(new RawNbsCustomInstrument("", "", 0, false)), reread.customInstruments());
    }

    @Test
    void nullArgumentsProduceControlledIoExceptions() throws IOException {
        assertRejected(null, "song is null");
        RawNbsSong valid = song(List.of(), List.of(), List.of());
        assertThrows(IOException.class, () -> NbsWriter.write(valid, (OutputStream) null));
        assertThrows(IOException.class, () -> NbsWriter.write(valid, (Path) null));
    }

    // Every invalid-input case checks BOTH APIs: no byte reaches a caller-owned stream, and an
    // existing target remains byte-identical with no temporary-file debris. Corpus round trips
    // continue to compare complete note lists in NbsWriterRoundTripTest, without filtering slots.
    private void assertRejected(RawNbsSong song, String message) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x5A);
        IOException streamFailure = assertThrows(IOException.class, () -> NbsWriter.write(song, output));
        assertTrue(streamFailure.getMessage().contains(message), streamFailure.getMessage());
        assertArrayEquals(new byte[]{0x5A}, output.toByteArray(), "validation appended output bytes");

        Path target = directory.resolve("previous.nbs");
        byte[] previous = {0x4E, 0x42, 0x53, (byte) 0xFF};
        Files.write(target, previous);
        IOException pathFailure = assertThrows(IOException.class, () -> NbsWriter.write(song, target));
        assertTrue(pathFailure.getMessage().contains(message), pathFailure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(target), "previous export changed");
        try (var files = Files.list(directory)) {
            assertEquals(List.of(target), files.toList(), "failed write leaked a temporary file");
        }
    }

    private static RawNbsSong song(List<RawNbsNote> notes, List<RawNbsLayer> layers,
                                   List<RawNbsCustomInstrument> instruments) {
        return new RawNbsSong(4, 20, 8, 1, "t", "a", "o", "d", 1000, 4,
                false, 0, 0, notes, layers, instruments);
    }

    private static RawNbsSong roundTrip(RawNbsSong song) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbsWriter.write(song, output);
        return NbsReader.read(output.toByteArray());
    }
}
