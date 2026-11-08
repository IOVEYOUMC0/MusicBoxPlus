package com.huidu.musicboxplus.module.edit.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Imports used to pick the parser from the file name alone, so a renamed or extension-less file was
// either rejected or handed to the wrong parser and failed with a misleading message. The sniffer
// reads the format's own header instead.
class MusicFileImporterFormatSniffTest {

    private final MusicFileImporter importer = MusicFileImporter.getInstance();

    private static byte[] midiHeader() {
        return new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6};
    }

    // v1+ NBS files open with a zero short, then the version byte, then the vanilla instrument count.
    private static byte[] nbsHeader(int version) {
        return new byte[]{0, 0, (byte) version, 20, 0, 2};
    }

    @Test
    void midiHeaderIsRecognisedWhateverTheNameSays() throws IOException {
        assertEquals(MusicFileImporter.ImportFormat.MIDI, importer.detectFormat(midiHeader(), "song.nbs"));
    }

    @Test
    void nbsHeaderIsRecognisedWhateverTheNameSays() throws IOException {
        assertEquals(MusicFileImporter.ImportFormat.NBS, importer.detectFormat(nbsHeader(4), "song.mid"));
        assertEquals(MusicFileImporter.ImportFormat.NBS, importer.detectFormat(nbsHeader(1), "no-extension"));
        assertEquals(MusicFileImporter.ImportFormat.NBS, importer.detectFormat(nbsHeader(6), "x"));
    }

    @Test
    void unrecognisedContentFallsBackToTheExtension() throws IOException {
        byte[] unknown = "not a song".getBytes(StandardCharsets.UTF_8);

        assertEquals(MusicFileImporter.ImportFormat.NBS, importer.detectFormat(unknown, "song.nbs"));
        assertEquals(MusicFileImporter.ImportFormat.MIDI, importer.detectFormat(unknown, "song.mid"));
        assertEquals(MusicFileImporter.ImportFormat.MIDI, importer.detectFormat(unknown, "song.midi"));
    }

    @Test
    void unrecognisedContentWithAnUnsupportedExtensionIsRejected() {
        byte[] unknown = "not a song".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> importer.detectFormat(unknown, "song.txt"));
        assertThrows(IOException.class, () -> importer.detectFormat(unknown, "song"));
    }

    @Test
    void versionByteOutsideTheSupportedRangeIsNotTreatedAsNbs() {
        // Version 0 files open with the song length, and a version above the supported range is a
        // different file shape; neither may be claimed as NBS by the sniff alone.
        assertNull(MusicFileImporter.sniffFormat(nbsHeader(0)));
        assertNull(MusicFileImporter.sniffFormat(nbsHeader(9)));
    }
}
