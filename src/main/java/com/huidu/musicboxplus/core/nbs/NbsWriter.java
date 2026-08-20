package com.huidu.musicboxplus.core.nbs;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Writes a RawNbsSong back out as a .nbs file, in format version 4.
//
// The inverse of NbsReader and the only writer in the plugin: exporting player-made music and
// persisting an auto-converted MIDI both come through here, so a round trip through the two
// classes is what the format round-trip test can assert.
//
// Version 4 rather than an older one because it is the first that stores per-note velocity,
// panning and fine pitch, and per-layer panning. Writing version 1 (which some earlier code did)
// silently flattens a song to full-volume centred notes; nothing in the pipeline can recover that
// afterwards.
//
// Every value goes out exactly as the record holds it: panning is the file's own 0..200 scale,
// with no inversion. NbsReader keeps that scale too, so the pair agrees.
//
// No org.bukkit here, matching the rest of this package.
public final class NbsWriter {

    private static final int NBS_VERSION = 4;
    private static final int DEFAULT_LAYER_VOLUME = 100;
    private static final int CENTERED_PANNING = 100;
    private static final int DEFAULT_CUSTOM_INSTRUMENT_PITCH = 45;

    private NbsWriter() {
    }

    public static void write(RawNbsSong song, Path out) throws IOException {
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            write(song, os);
        }
    }

    public static void write(RawNbsSong song, OutputStream os) throws IOException {
        List<RawNbsNote> notes = new ArrayList<>(song.notes());
        notes.sort(Comparator.comparingInt(RawNbsNote::tick).thenComparingInt(RawNbsNote::layer));

        int maxLayer = -1;
        int maxTick = 0;
        for (RawNbsNote note : notes) {
            maxLayer = Math.max(maxLayer, note.layer());
            maxTick = Math.max(maxTick, note.tick());
        }
        int songHeight = Math.max(1, Math.max(song.songHeight(), maxLayer + 1));
        int lengthTicks = Math.max(song.lengthTicks(), maxTick);

        writeShortLE(os, 0);
        os.write(NBS_VERSION);
        os.write(clampByte(song.vanillaInstrumentCount()));
        writeShortLE(os, lengthTicks);
        writeShortLE(os, songHeight);
        writeString(os, song.title());
        writeString(os, song.author());
        writeString(os, song.originalAuthor());
        writeString(os, song.description());
        writeShortLE(os, clampTempo(song.tempoRaw()));
        os.write(0);
        os.write(0);
        os.write(clampByte(song.timeSignature()));
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeString(os, "");
        os.write(song.loopEnabled() ? 1 : 0);
        os.write(clampByte(song.maxLoopCount()));
        writeShortLE(os, Math.max(0, song.loopStartTick()));

        writeNotes(os, notes);
        writeLayers(os, song.layers(), songHeight);
        writeCustomInstruments(os, song.customInstruments());
    }

    // Tick-jump / layer-jump scheme: both counters start at -1 and each record stores the
    // distance from the previous one, with a zero jump terminating the run.
    private static void writeNotes(OutputStream os, List<RawNbsNote> sortedNotes) throws IOException {
        int previousTick = -1;
        int index = 0;
        while (index < sortedNotes.size()) {
            int tick = sortedNotes.get(index).tick();
            writeShortLE(os, tick - previousTick);
            previousTick = tick;

            int previousLayer = -1;
            while (index < sortedNotes.size() && sortedNotes.get(index).tick() == tick) {
                RawNbsNote note = sortedNotes.get(index);
                // Two notes on the same layer at the same tick cannot both be written: the layer
                // jump would be zero, which the format reads as end-of-tick. Dropping the later
                // one keeps the file readable; keeping it would truncate every remaining note of
                // that tick instead.
                if (note.layer() == previousLayer) {
                    index++;
                    continue;
                }
                writeShortLE(os, note.layer() - previousLayer);
                previousLayer = note.layer();
                os.write(clampByte(note.instrument()));
                os.write(clampByte(note.key()));
                os.write(clampByte(note.velocity()));
                os.write(clampByte(note.panning()));
                writeShortLE(os, note.finePitch());
                index++;
            }
            writeShortLE(os, 0);
        }
        writeShortLE(os, 0);
    }

    // Exactly songHeight fixed-length records in index order: no jump prefix and no terminator.
    // Layers the song does not declare are written as defaults, because a short section would
    // leave the reader taking the custom instrument count out of the middle of a name.
    private static void writeLayers(OutputStream os, List<RawNbsLayer> layers, int songHeight) throws IOException {
        RawNbsLayer[] byIndex = new RawNbsLayer[songHeight];
        for (RawNbsLayer layer : layers) {
            if (layer != null && layer.index() >= 0 && layer.index() < songHeight) {
                byIndex[layer.index()] = layer;
            }
        }
        for (int i = 0; i < songHeight; i++) {
            RawNbsLayer layer = byIndex[i];
            writeString(os, layer == null ? "" : layer.name());
            os.write(layer != null && layer.locked() ? 1 : 0);
            os.write(layer == null ? DEFAULT_LAYER_VOLUME : clampByte(layer.volume()));
            os.write(layer == null ? CENTERED_PANNING : clampByte(layer.panning()));
        }
    }

    // The table has to be written for real: notes carry ids at or above the vanilla count, so
    // declaring zero custom instruments points them at an empty table on re-import.
    private static void writeCustomInstruments(OutputStream os, List<RawNbsCustomInstrument> instruments) throws IOException {
        // An unsigned byte holds the count, so anything past 255 cannot be declared at all.
        int count = Math.min(255, instruments.size());
        os.write(count);
        for (int i = 0; i < count; i++) {
            RawNbsCustomInstrument instrument = instruments.get(i);
            writeString(os, instrument.name());
            writeString(os, instrument.soundFile());
            os.write(instrument.pitch() <= 0 ? DEFAULT_CUSTOM_INSTRUMENT_PITCH : clampByte(instrument.pitch()));
            os.write(instrument.pressKey() ? 1 : 0);
        }
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int clampTempo(int tempoRaw) {
        return Math.max(1, Math.min(65535, tempoRaw));
    }

    private static void writeShortLE(OutputStream os, int value) throws IOException {
        os.write(value & 0xFF);
        os.write((value >> 8) & 0xFF);
    }

    private static void writeIntLE(OutputStream os, int value) throws IOException {
        os.write(value & 0xFF);
        os.write((value >> 8) & 0xFF);
        os.write((value >> 16) & 0xFF);
        os.write((value >> 24) & 0xFF);
    }

    // ISO-8859-1: the format stores one byte per character, matching NbsReader and Note Block
    // Studio. UTF-8 makes non-ASCII titles mojibake in every other NBS tool; characters above
    // U+00FF have no representation at all and become '?'.
    private static void writeString(OutputStream os, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.ISO_8859_1);
        writeIntLE(os, bytes.length);
        os.write(bytes);
    }
}
