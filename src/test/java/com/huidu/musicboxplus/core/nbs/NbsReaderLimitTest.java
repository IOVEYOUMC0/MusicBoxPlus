package com.huidu.musicboxplus.core.nbs;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NbsReaderLimitTest {

    @Test
    void rejectsOversizedInputBeforeParsing() {
        assertThrows(IOException.class, () -> NbsReader.read(new byte[NbsReader.MAX_FILE_BYTES + 1]));
    }

    @Test
    void rejectsTooManyNotesWhileParsing() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u16(out, 0); // v1 marker
        out.write(1); // version
        out.write(10); // vanilla instruments
        u16(out, 1); // layers
        for (int i = 0; i < 4; i++) {
            str(out, "");
        }
        u16(out, 1000); // tempo
        out.write(0);
        out.write(0);
        out.write(4);
        i32(out, 0);
        i32(out, 0);
        i32(out, 0);
        i32(out, 0);
        i32(out, 0);
        str(out, "");

        for (int i = 0; i < NbsReader.MAX_NOTES + 1; i++) {
            u16(out, 1); // next tick
            u16(out, 1); // layer 0
            out.write(0);
            out.write(45);
            u16(out, 0); // end layers for this tick
        }
        u16(out, 0); // end notes

        assertThrows(IOException.class, () -> NbsReader.read(out.toByteArray()));
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void i32(ByteArrayOutputStream out, int value) {
        for (int shift = 0; shift < 32; shift += 8) {
            out.write((value >>> shift) & 0xFF);
        }
    }

    private static void str(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        i32(out, bytes.length);
        out.writeBytes(bytes);
    }
}
