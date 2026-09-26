package com.huidu.musicboxplus.core.nbs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// read() and readMetadata() are two ways of parsing the same header, and every song's displayed
// title and duration come from the cheap one while playback comes from the full one. They used to
// carry separate copies of the header parse, and the copies had drifted: readMetadata replaced
// v0's header length with the tick of the last note. No file in either corpus exposed it, because
// songs normally end on their last note -- so the case that would have caught it is built here.
class NbsReaderMetadataParityTest {

    @Test
    void bothPathsAgreeOnEveryHeaderFieldAcrossTheCorpus() throws IOException {
        List<Path> files = corpus(NbsCorpus.BUNDLED);
        assertTrue(!files.isEmpty(), "bundled corpus is missing");
        for (Path file : files) {
            assertHeadersMatch(NbsReader.read(file), NbsReader.readMetadata(file),
                    file.getFileName().toString());
        }
    }

    @Test
    void bothPathsAgreeAcrossTheExtendedCorpus() throws IOException {
        Path root = NbsCorpus.extendedRoot();
        Assumptions.assumeTrue(root != null, "extended corpus not present");
        for (Path file : corpus(root)) {
            assertHeadersMatch(NbsReader.read(file), NbsReader.readMetadata(file),
                    file.getFileName().toString());
        }
    }

    // A v0 song whose header says 400 ticks while its last note lands on tick 8: the song ends in
    // silence. This is the shape that told the two paths apart, and no corpus file has it.
    @Test
    void versionZeroKeepsItsHeaderLengthWhenTheSongEndsInSilence() throws IOException {
        byte[] data = versionZeroWithTrailingSilence(400, 8);

        RawNbsSong full = NbsReader.read(data);
        RawNbsSong meta = NbsReader.readMetadata(data);

        assertEquals(0, full.version());
        assertEquals(400, full.lengthTicks(), "v0 carries a real length field; it is authoritative");
        assertEquals(400, meta.lengthTicks(), "the metadata path must not substitute the last note's tick");
        assertEquals(8, full.notes().get(full.notes().size() - 1).tick());
    }

    private static void assertHeadersMatch(RawNbsSong full, RawNbsSong meta, String label) {
        assertEquals(full.version(), meta.version(), label + ": version");
        assertEquals(full.vanillaInstrumentCount(), meta.vanillaInstrumentCount(), label + ": vanilla count");
        assertEquals(full.lengthTicks(), meta.lengthTicks(), label + ": length");
        assertEquals(full.songHeight(), meta.songHeight(), label + ": height");
        assertEquals(full.title(), meta.title(), label + ": title");
        assertEquals(full.author(), meta.author(), label + ": author");
        assertEquals(full.originalAuthor(), meta.originalAuthor(), label + ": original author");
        assertEquals(full.description(), meta.description(), label + ": description");
        assertEquals(full.tempoRaw(), meta.tempoRaw(), label + ": tempo");
        assertEquals(full.timeSignature(), meta.timeSignature(), label + ": time signature");
        assertEquals(full.loopEnabled(), meta.loopEnabled(), label + ": loop enabled");
        assertEquals(full.maxLoopCount(), meta.maxLoopCount(), label + ": max loop count");
        assertEquals(full.loopStartTick(), meta.loopStartTick(), label + ": loop start");
    }

    // Hand-built because NbsWriter only emits version 4, and the divergence lived in v0.
    private static byte[] versionZeroWithTrailingSilence(int headerLength, int lastNoteTick) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, headerLength);   // v0 opens with the length, which is also the version probe
        u16(out, 1);              // songHeight
        str(out, "silent tail");
        str(out, "author");
        str(out, "original");
        str(out, "description");
        u16(out, 1000);           // tempo, 10 t/s
        out.write(0);             // autoSave
        out.write(0);             // autoSaveDuration
        out.write(4);             // timeSignature
        for (int i = 0; i < 5; i++) {
            i32(out, 0);          // minutes spent, clicks, blocks added/removed
        }
        str(out, "");             // imported file name

        // One note at lastNoteTick, layer 0. v0 stores instrument+key only.
        u16(out, lastNoteTick + 1);   // tick jump from -1
        u16(out, 1);                  // layer jump from -1
        out.write(0);                 // instrument
        out.write(45);                // key
        u16(out, 0);                  // end of layers at this tick
        u16(out, 0);                  // end of notes
        return out.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void i32(ByteArrayOutputStream out, int value) {
        for (int shift = 0; shift < 32; shift += 8) {
            out.write((value >> shift) & 0xFF);
        }
    }

    private static void str(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        i32(out, bytes.length);
        out.write(bytes);
    }

    private static List<Path> corpus(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                    .forEach(files::add);
        }
        return files;
    }
}
