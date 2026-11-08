package com.huidu.musicboxplus.core.nbs;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private NbsWriter() {
    }

    public static void write(RawNbsSong song, Path out) throws IOException {
        if (out == null) {
            throw new IOException("NBS output path is null");
        }
        PreparedSong prepared = prepare(song);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Validate before touching the filesystem, then write to a sibling temp file and replace
        // the target only after a successful close. I/O failures must not truncate the old export.
        Path temp = Files.createTempFile(parent, ".nbs-", ".tmp");
        try {
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(temp))) {
                writePrepared(song, prepared, os);
            }
            replace(temp, out);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void write(RawNbsSong song, OutputStream os) throws IOException {
        if (os == null) {
            throw new IOException("NBS output stream is null");
        }
        writePrepared(song, prepare(song), os);
    }

    private record PreparedSong(List<RawNbsNote> notes, RawNbsLayer[] layers, int lengthTicks) {
    }

    // All model validation precedes the first output byte, including fields in trailing sections.
    // RawNbsSong already snapshots its lists (and normalizes null lists to empty); nullable strings
    // retain the existing empty-string encoding. Validate the raw u8 range, not playback limits:
    // NbsReader supports unsigned keys above 87 and preserves all byte-valued volume/pan fields.
    private static PreparedSong prepare(RawNbsSong song) throws IOException {
        if (song == null) {
            throw new IOException("NBS song is null");
        }
        requireRange("vanilla instrument count", song.vanillaInstrumentCount(), 0, 255);
        requireRange("song length", song.lengthTicks(), 0, 0xFFFF);
        requireRange("song height", song.songHeight(), 0, 0xFFFF);
        requireRange("tempo", song.tempoRaw(), 0, 0xFFFF);
        requireRange("time signature", song.timeSignature(), 0, 255);
        requireRange("max loop count", song.maxLoopCount(), 0, 255);
        requireRange("loop start tick", song.loopStartTick(), 0, 0xFFFF);
        requireRange("custom instrument count", song.customInstruments().size(), 0, 255);

        List<RawNbsNote> notes = new ArrayList<>(song.notes());
        int songHeight = Math.max(1, song.songHeight());
        int lengthTicks = song.lengthTicks();
        for (RawNbsNote note : notes) {
            requireRange("note tick", note.tick(), 0, 0xFFFF);
            // Height is a count, not an index: index 65535 would need height 65536 in v4.
            requireRange("note layer", note.layer(), 0, 0xFFFE);
            requireRange("note instrument", note.instrument(), 0, 255);
            requireRange("note key", note.key(), 0, 255);
            requireRange("note velocity", note.velocity(), 0, 255);
            requireRange("note panning", note.panning(), 0, 255);
            requireRange("note fine pitch", note.finePitch(), Short.MIN_VALUE, Short.MAX_VALUE);
            songHeight = Math.max(songHeight, note.layer() + 1);
            lengthTicks = Math.max(lengthTicks, note.tick());
        }
        notes.sort(Comparator.comparingInt(RawNbsNote::tick).thenComparingInt(RawNbsNote::layer));
        for (int i = 1; i < notes.size(); i++) {
            RawNbsNote previous = notes.get(i - 1);
            RawNbsNote note = notes.get(i);
            if (note.tick() == previous.tick() && note.layer() == previous.layer()) {
                throw new IOException("NBS duplicate note at tick " + note.tick() + ", layer " + note.layer());
            }
        }
        for (RawNbsLayer layer : song.layers()) {
            requireRange("layer index", layer.index(), 0, 0xFFFE);
            requireRange("layer volume", layer.volume(), 0, 255);
            requireRange("layer panning", layer.panning(), 0, 255);
            songHeight = Math.max(songHeight, layer.index() + 1);
        }
        RawNbsLayer[] layers = new RawNbsLayer[songHeight];
        for (RawNbsLayer layer : song.layers()) {
            if (layers[layer.index()] != null) {
                throw new IOException("NBS duplicate layer index: " + layer.index());
            }
            layers[layer.index()] = layer;
        }
        for (RawNbsCustomInstrument instrument : song.customInstruments()) {
            requireRange("custom instrument pitch", instrument.pitch(), 0, 255);
        }
        // Deliberately keep v4's existing u16 capacity. A v5/v6 capacity/version strategy remains
        // unresolved; accepting a newer source version does not justify inventing its wire format.
        return new PreparedSong(notes, layers, lengthTicks);
    }

    private static void requireRange(String field, int value, int min, int max) throws IOException {
        if (value < min || value > max) {
            throw new IOException("NBS " + field + " out of range [" + min + ", " + max + "]: " + value);
        }
    }

    private static void writePrepared(RawNbsSong song, PreparedSong prepared, OutputStream os) throws IOException {
        writeShortLE(os, 0);
        os.write(NBS_VERSION);
        os.write(song.vanillaInstrumentCount());
        writeShortLE(os, prepared.lengthTicks());
        writeShortLE(os, prepared.layers().length);
        writeString(os, song.title());
        writeString(os, song.author());
        writeString(os, song.originalAuthor());
        writeString(os, song.description());
        writeShortLE(os, song.tempoRaw());
        os.write(0);
        os.write(0);
        os.write(song.timeSignature());
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeIntLE(os, 0);
        writeString(os, "");
        os.write(song.loopEnabled() ? 1 : 0);
        os.write(song.maxLoopCount());
        writeShortLE(os, song.loopStartTick());

        writeNotes(os, prepared.notes());
        writeLayers(os, prepared.layers());
        writeCustomInstruments(os, song.customInstruments());
    }

    // Tick-jump / layer-jump scheme: both counters start at -1 and each record stores the
    // distance from the previous one, with a zero jump terminating the run.
    private static void writeNotes(OutputStream os, List<RawNbsNote> sortedNotes) throws IOException {
        int previousTick = -1;
        int index = 0;
        while (index < sortedNotes.size()) {
            int tick = sortedNotes.get(index).tick();
            int tickJump = tick - previousTick;
            if (tickJump <= 0) {
                throw new IOException("NBS tick jump out of range: " + tickJump);
            }
            // A jump is a 16-bit value, but the gap to the next run can be larger: the first note at
            // tick 65535 is a jump of 65536 from -1. Emit empty runs in 0xFFFF-tick steps -- each is a
            // jump plus its terminator, i.e. "no notes in this span" -- and the remainder after them.
            while (tickJump > 0xFFFF) {
                writeShortLE(os, 0xFFFF);
                writeShortLE(os, 0);
                previousTick += 0xFFFF;
                tickJump -= 0xFFFF;
            }
            writeShortLE(os, tickJump);
            previousTick = tick;

            int previousLayer = -1;
            while (index < sortedNotes.size() && sortedNotes.get(index).tick() == tick) {
                RawNbsNote note = sortedNotes.get(index);
                int layerJump = note.layer() - previousLayer;
                if (layerJump <= 0 || layerJump > 0xFFFF) {
                    throw new IOException("NBS layer jump out of range: " + layerJump);
                }
                writeShortLE(os, layerJump);
                previousLayer = note.layer();
                os.write(note.instrument());
                os.write(note.key());
                os.write(note.velocity());
                os.write(note.panning());
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
    private static void writeLayers(OutputStream os, RawNbsLayer[] layers) throws IOException {
        for (RawNbsLayer layer : layers) {
            writeString(os, layer == null ? "" : layer.name());
            os.write(layer != null && layer.locked() ? 1 : 0);
            os.write(layer == null ? DEFAULT_LAYER_VOLUME : layer.volume());
            os.write(layer == null ? CENTERED_PANNING : layer.panning());
        }
    }

    // The table has to be written for real: notes carry ids at or above the vanilla count, so
    // declaring zero custom instruments points them at an empty table on re-import.
    private static void writeCustomInstruments(OutputStream os, List<RawNbsCustomInstrument> instruments) throws IOException {
        int count = instruments.size();
        os.write(count);
        for (int i = 0; i < count; i++) {
            RawNbsCustomInstrument instrument = instruments.get(i);
            writeString(os, instrument.name());
            writeString(os, instrument.soundFile());
            os.write(instrument.pitch());
            os.write(instrument.pressKey() ? 1 : 0);
        }
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

    // One byte per character, which is what the format is and what the whole ecosystem reads.
    //
    // NoteBlockAPI -- the library every other NBS plugin here was built on -- reads a header string
    // as `char c = (char) dataInputStream.readByte()` (see Reference/NoteBlockAPI/.../NBSDecoder),
    // and NbsReader decodes each byte as U+00XX for the same reason. So the byte for a character is
    // its low byte, and that is what is written here.
    //
    // Writing through ISO-8859-1 instead mapped every character above U+00FF to '?' -- so a Chinese
    // song name, which is the normal case on a Chinese server, was destroyed on the way out even
    // though its low byte was available and is what every NBS tool expects to find. The character
    // itself still cannot survive (the field is one byte wide), but the information the file can
    // hold now survives, and an import/export round trip of an existing file is byte-identical.
    private static void writeString(OutputStream os, String value) throws IOException {
        String text = value == null ? "" : value;
        // The byte count is the character count: that is the whole point of this method.
        writeIntLE(os, text.length());
        for (int i = 0; i < text.length(); i++) {
            os.write(text.charAt(i) & 0xFF);
        }
    }
}
