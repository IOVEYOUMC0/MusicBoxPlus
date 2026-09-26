package com.huidu.musicboxplus.core.nbs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// NbsWriter is the inverse of NbsReader, so reading a file, writing it back and reading that
// again has to return the same song. The pair is the only thing standing between a player's
// exported music and a file no other tool can open, and a field written at the wrong offset
// shifts everything after it -- which is exactly what a round trip catches and a spot check of
// individual values does not.
class NbsWriterRoundTripTest {

    @Test
    void roundTripsEveryFileInTheBundledCorpus() throws IOException {
        List<Path> files = corpusFiles(NbsCorpus.BUNDLED);
        assertTrue(files.size() > 0, "bundled corpus is missing");
        for (Path file : files) {
            assertRoundTrips(NbsReader.read(file), file.getFileName().toString());
        }
    }

    @Test
    void roundTripsEveryFileInTheExtendedCorpus() throws IOException {
        Path root = NbsCorpus.extendedRoot();
        Assumptions.assumeTrue(root != null, "extended corpus not present");
        for (Path file : corpusFiles(root)) {
            assertRoundTrips(NbsReader.read(file), file.getFileName().toString());
        }
    }

    // A song with no notes at all still has to produce a file the reader accepts: the note
    // section collapses to its terminator alone, and getting that wrong makes the layer section
    // start two bytes early.
    @Test
    void roundTripsASongWithNoNotes() throws IOException {
        RawNbsSong empty = new RawNbsSong(4, 20, 0, 1, "empty", "a", "b", "c", 1000, 4,
                false, 0, 0, List.of(), List.of(), List.of());
        RawNbsSong reread = writeAndRead(empty);
        assertEquals(0, reread.notes().size());
        assertEquals("empty", reread.title());
    }

    // Values a version 1 or 2 file cannot carry -- velocity, note panning, fine pitch, layer
    // panning, custom instruments -- must survive, because the writer emits version 4.
    @Test
    void preservesFieldsOlderFormatVersionsCannotHold() throws IOException {
        RawNbsSong song = new RawNbsSong(4, 20, 8, 2, "t", "a", "o", "d", 1234, 3,
                true, 5, 6,
                List.of(new RawNbsNote(0, 0, 21, 45, 37, 0, -85),
                        new RawNbsNote(4, 1, 3, 60, 100, 200, 42)),
                List.of(new RawNbsLayer(0, "left", true, 73, 0),
                        new RawNbsLayer(1, "right", false, 100, 200)),
                List.of(new RawNbsCustomInstrument("pad", "pad.ogg", 45, false)));

        RawNbsSong reread = writeAndRead(song);

        assertEquals(song.notes(), reread.notes());
        assertEquals(song.layers(), reread.layers());
        assertEquals(song.customInstruments(), reread.customInstruments());
        assertEquals(1234, reread.tempoRaw());
        assertEquals(3, reread.timeSignature());
        assertTrue(reread.loopEnabled());
        assertEquals(5, reread.maxLoopCount());
        assertEquals(6, reread.loopStartTick());
    }

    // Titles outside ASCII are the usual casualty of a charset mismatch: written as UTF-8 they
    // read back as two garbage characters each.
    @Test
    void preservesNonAsciiTextInTheLatinRange() throws IOException {
        RawNbsSong song = new RawNbsSong(4, 20, 1, 1, "Café", "Ørn", "Ñ", "üñî",
                1000, 4, false, 0, 0, List.of(), List.of(), List.of());
        RawNbsSong reread = writeAndRead(song);
        assertEquals("Café", reread.title());
        assertEquals("Ørn", reread.author());
        assertEquals("Ñ", reread.originalAuthor());
        assertEquals("üñî", reread.description());
    }

    // A Chinese title cannot be stored (the field is one byte per character) but it must not be
    // thrown away as '?' either: the byte written for a character is its low byte, which is what
    // NoteBlockAPI reads back and what every NBS file in the wild contains for CJK. Writing through
    // ISO-8859-1 replaced all five characters with '?'.
    @Test
    void writesTheLowByteOfCharactersTheFormatCannotHold() throws IOException {
        RawNbsSong song = new RawNbsSong(4, 20, 1, 1, "忘情牛肉面", "", "", "",
                1000, 4, false, 0, 0, List.of(), List.of(), List.of());

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbsWriter.write(song, buffer);
        byte[] data = buffer.toByteArray();

        // Title length (5, little endian) followed by the low byte of each character.
        byte[] expected = {0x05, 0x00, 0x00, 0x00, (byte) 0xD8, (byte) 0xC5, 0x5B, (byte) 0x89, 0x62};
        int at = indexOf(data, expected);
        assertTrue(at >= 0, "expected the low bytes of the title at some offset in " + hex(data));
        assertTrue(indexOf(data, new byte[]{0x3F, 0x3F, 0x3F, 0x3F, 0x3F}) < 0,
                "the title was written as '?????'");

        // And the reader gives back exactly those bytes, character for character, so an
        // export/import round trip of such a file is stable.
        RawNbsSong reread = NbsReader.read(data);
        assertEquals("\u00D8\u00C5[\u0089b", reread.title(),
                "the reader decodes one byte per character; the round trip must be byte-stable");
        assertEquals(reread.title(), writeAndRead(reread).title());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte b : data) {
            text.append(String.format("%02x ", b));
        }
        return text.toString().trim();
    }

    @Test
    void rejectsValuesThatDoNotFitTheNbsFormat() {
        RawNbsSong tooLong = new RawNbsSong(4, 20, 65536, 1, "long", "", "", "",
                1000, 4, false, 0, 0, List.of(), List.of(), List.of());
        assertThrows(IOException.class, () -> NbsWriter.write(tooLong, new ByteArrayOutputStream()));
    }

    private static void assertRoundTrips(RawNbsSong original, String label) throws IOException {
        RawNbsSong reread = writeAndRead(original);

        assertEquals(original.notes(), reread.notes(), label + ": notes differ");
        assertEquals(original.customInstruments(), reread.customInstruments(),
                label + ": custom instruments differ");
        assertEquals(original.title(), reread.title(), label + ": title differs");
        assertEquals(original.author(), reread.author(), label + ": author differs");
        assertEquals(original.originalAuthor(), reread.originalAuthor(), label + ": original author differs");
        assertEquals(original.description(), reread.description(), label + ": description differs");
        assertEquals(original.tempoRaw(), reread.tempoRaw(), label + ": tempo differs");

        // Only the layers the source declared are compared. A file may end before its layer
        // section (NbsReader allows that, and some converters produce it), in which case the
        // writer fills the gap with defaults and the reread song legitimately has more.
        for (int i = 0; i < original.layers().size(); i++) {
            assertEquals(original.layers().get(i), reread.layers().get(i), label + ": layer " + i + " differs");
        }
    }

    private static RawNbsSong writeAndRead(RawNbsSong song) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbsWriter.write(song, buffer);
        return NbsReader.read(buffer.toByteArray());
    }

    private static List<Path> corpusFiles(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                    .forEach(files::add);
        }
        return files;
    }
}
