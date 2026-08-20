package com.huidu.musicboxplus.core.nbs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Reads .nbs files per the public openNBS specification.
//
// Deliberately imports nothing from org.bukkit: parsing is pure I/O plus arithmetic, which keeps
// the whole format layer coverable by plain unit tests (see NbsReaderReferenceTest for the
// against-reference comparison).
//
// The cursor exposes exactly five primitives -- u8/u16/i16/i32/str -- and deliberately offers no
// readByte / readShort. Nearly every NBS field is unsigned, and a signed read silently truncates:
// the back half of a long song disappears, instrument ids go negative, the custom-instrument count
// turns negative. Nothing throws, it just sounds wrong. Removing signed reads from the toolbox
// makes that class of bug unreachable.
public final class NbsReader {

    private NbsReader() {
    }

    public static RawNbsSong read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    public static RawNbsSong read(InputStream rawStream) throws IOException {
        return read(rawStream.readAllBytes());
    }

    public static RawNbsSong read(byte[] data) throws IOException {
        Cursor c = new Cursor(data);
        Header h = readHeader(c);

        List<RawNbsNote> notes = readNotes(c, h.version);
        int lengthTicks = resolveLength(h, notes);
        List<RawNbsLayer> layers = readLayers(c, h.version, h.songHeight);
        List<RawNbsCustomInstrument> instruments = readCustomInstruments(c);

        return h.toSong(lengthTicks, List.copyOf(notes), List.copyOf(layers), List.copyOf(instruments));
    }

    // Metadata-only read for songs that only need the header (title, length, tempo, author).
    // The full note graph is skipped, so catalog/reload paths over a large library no longer
    // parse every note just to display a title and a duration.
    //
    // v3+ carries lengthTicks in the header, so parsing can stop before the note section.
    // v1/v2 must still walk the note section to derive the length from the last note's tick.
    public static RawNbsSong readMetadata(Path file) throws IOException {
        return readMetadata(Files.readAllBytes(file));
    }

    public static RawNbsSong readMetadata(byte[] data) throws IOException {
        Cursor c = new Cursor(data);
        Header h = readHeader(c);
        // Only the versions that have no length in the header pay for the note walk.
        int lengthTicks = needsNoteWalk(h.version) ? resolveLength(h, readNotes(c, h.version)) : h.lengthTicks;
        return h.toSong(lengthTicks, List.of(), List.of(), List.of());
    }

    // Everything up to the note section. Shared by both entry points: they used to carry their own
    // copy, and the copies had already drifted -- readMetadata overwrote v0's header length with
    // the last note's tick, which read() correctly leaves alone.
    private static Header readHeader(Cursor c) throws IOException {
        Header h = new Header();

        // Version probe: v1+ files open with a zero short, v0 files open with the song length.
        int first = c.u16("headerProbe");
        if (first == 0) {
            h.version = c.u8("version");
            h.vanillaInstrumentCount = c.u8("vanillaInstrumentCount");
            // The length field was only added in v3. v1/v2 have no such two bytes, and reading
            // them anyway shifts every later field, which shows up as string lengths in the
            // hundreds of millions and an outright parse failure. For those versions the length
            // is derived from the last note's tick instead; see resolveLength.
            h.lengthTicks = h.version >= 3 ? c.u16("lengthTicks") : 0;
        } else {
            h.version = 0;
            h.vanillaInstrumentCount = 10;   // v0 always has exactly 10 vanilla instruments
            h.lengthTicks = first;           // v0 does carry a length, and it is authoritative
        }

        h.songHeight = c.u16("songHeight");
        h.title = c.str("title");
        h.author = c.str("author");
        h.originalAuthor = c.str("originalAuthor");
        h.description = c.str("description");
        h.tempoRaw = c.u16("tempo");
        c.u8("autoSave");
        c.u8("autoSaveDuration");
        h.timeSignature = c.u8("timeSignature");
        c.i32("minutesSpent");
        c.i32("leftClicks");
        c.i32("rightClicks");
        c.i32("noteBlocksAdded");
        c.i32("noteBlocksRemoved");
        c.str("midiOrSchematicFileName");

        if (h.version >= 4) {
            h.loopEnabled = c.u8("loopEnabled") != 0;
            h.maxLoopCount = c.u8("maxLoopCount");
            h.loopStartTick = c.u16("loopStartTick");
        }
        return h;
    }

    // v1 and v2 alone have no length field. v0 has one in the position the probe already read, and
    // v3+ has one of its own, so for those the header value stands even when the last note falls
    // short of it -- a song may legitimately end in silence.
    private static boolean needsNoteWalk(int version) {
        return version >= 1 && version <= 2;
    }

    private static int resolveLength(Header h, List<RawNbsNote> notes) {
        return needsNoteWalk(h.version) ? lastTick(notes) : h.lengthTicks;
    }

    // Mutable while parsing, then frozen into the record. A plain field holder rather than a
    // constructor with sixteen positional arguments, which is how a header field ends up silently
    // swapped with its neighbour.
    private static final class Header {
        int version;
        int vanillaInstrumentCount;
        int lengthTicks;
        int songHeight;
        String title;
        String author;
        String originalAuthor;
        String description;
        int tempoRaw;
        int timeSignature;
        boolean loopEnabled;
        int maxLoopCount;
        int loopStartTick;

        RawNbsSong toSong(int lengthTicks, List<RawNbsNote> notes, List<RawNbsLayer> layers,
                          List<RawNbsCustomInstrument> instruments) {
            return new RawNbsSong(version, vanillaInstrumentCount, lengthTicks, songHeight,
                    title, author, originalAuthor, description, tempoRaw, timeSignature,
                    loopEnabled, maxLoopCount, loopStartTick, notes, layers, instruments);
        }
    }

    // Song length for v1/v2: absent from the header, so it is the tick of the last note.
    private static int lastTick(List<RawNbsNote> notes) {
        return notes.isEmpty() ? 0 : notes.get(notes.size() - 1).tick();
    }

    // Note section: outer loop steps by tick jumps, inner loop by layer jumps, each terminated by 0.
    private static List<RawNbsNote> readNotes(Cursor c, int version) throws IOException {
        List<RawNbsNote> notes = new ArrayList<>();
        int tick = -1;
        while (true) {
            int tickJump = c.u16("tickJump");
            if (tickJump == 0) {
                break;
            }
            tick += tickJump;
            int layer = -1;
            while (true) {
                int layerJump = c.u16("layerJump");
                if (layerJump == 0) {
                    break;
                }
                layer += layerJump;
                int instrument = c.u8("noteInstrument");
                int key = c.u8("noteKey");
                int velocity = 100;
                int panning = 100;
                int finePitch = 0;
                if (version >= 4) {
                    velocity = c.u8("noteVelocity");
                    panning = c.u8("notePanning");
                    finePitch = c.i16("noteFinePitch");   // the only signed field in the format
                }
                notes.add(new RawNbsNote(tick, layer, instrument, key, velocity, panning, finePitch));
            }
        }
        return notes;
    }

    // Layers are exactly songHeight fixed-length records in order: no jump prefix, no
    // terminator.
    //
    // The whole section is optional, and so is the one after it. A file may simply end
    // once the notes are written, which some MIDI converters and web tools do; Note Block
    // Studio opens those. Stopping at end-of-input rather than failing keeps such a song
    // playable with default layer settings instead of rejecting it outright.
    private static List<RawNbsLayer> readLayers(Cursor c, int version, int songHeight) throws IOException {
        List<RawNbsLayer> layers = new ArrayList<>(Math.max(0, songHeight));
        for (int i = 0; i < songHeight; i++) {
            if (c.atEnd()) {
                break;
            }
            String name = c.str("layerName");
            boolean locked = version >= 4 && c.u8("layerLocked") != 0;
            int volume = c.u8("layerVolume");
            int panning = version >= 2 ? c.u8("layerPanning") : 100;
            layers.add(new RawNbsLayer(i, name, locked, volume, panning));
        }
        return layers;
    }

    // Custom instruments: an unsigned byte count, so at most 255. Optional like the layer
    // section above.
    private static List<RawNbsCustomInstrument> readCustomInstruments(Cursor c) throws IOException {
        if (c.atEnd()) {
            return List.of();
        }
        int count = c.u8("customInstrumentCount");
        List<RawNbsCustomInstrument> instruments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = c.str("customInstrumentName");
            String soundFile = c.str("customInstrumentSoundFile");
            int pitch = c.u8("customInstrumentPitch");
            boolean pressKey = c.u8("customInstrumentPressKey") != 0;
            instruments.add(new RawNbsCustomInstrument(name, soundFile, pitch, pressKey));
        }
        return instruments;
    }

    // Little-endian read cursor over the whole file held in memory.
    //
    // Parsing reads one byte at a time by nature, so going through a stream costs a virtual
    // call per byte, and a .nbs is small enough that there is nothing to gain from streaming:
    // the largest song in a 279-file library is under 400 KB. Indexing an array instead makes
    // the reader several times faster, turns the end-of-input check into a comparison, and
    // lets strings be decoded straight out of the buffer with no intermediate copy.
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        // Whether the data is exhausted, used to tell an omitted trailing section from a
        // truncated file. Note that running out of data is not the same as reaching the end
        // of the file: many songs are zero-padded to a power-of-two size, so parsing
        // legitimately finishes with bytes to spare. Never assert that parsing consumed the
        // whole file.
        boolean atEnd() {
            return pos >= data.length;
        }

        private int nextByte(String field) throws IOException {
            if (pos >= data.length) {
                throw new NbsFormatException(field, pos, "unexpected end of file");
            }
            return data[pos++] & 0xFF;
        }

        int u8(String field) throws IOException {
            return nextByte(field);
        }

        int u16(String field) throws IOException {
            int lo = nextByte(field);
            int hi = nextByte(field);
            return lo | (hi << 8);
        }

        int i16(String field) throws IOException {
            return (short) u16(field);
        }

        int i32(String field) throws IOException {
            int b0 = nextByte(field);
            int b1 = nextByte(field);
            int b2 = nextByte(field);
            int b3 = nextByte(field);
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }

        // A string is a little-endian int length followed by that many bytes.
        //
        // Decoded as ISO-8859-1, one byte per char. The format spec does not name a
        // charset, and real files are not UTF-8: the writer stores only the low byte of
        // each UTF-16 code unit. A CJK title U+65B0 U+5B9D U+5C9B is written as the three
        // bytes b0 9d 9b, exactly those low bytes. UTF-8 decoding does not fail on those,
        // it silently yields U+FFFD, which is unrecoverable -- so the bytes could never
        // be written back unchanged. ISO-8859-1 round-trips every byte and additionally
        // decodes the Latin-1 range correctly, which is where the readable titles live
        // ("Pokémon Center Theme" is stored as 50 6f 6b e9 ...).
        String str(String field) throws IOException {
            int length = i32(field + ".length");
            if (length < 0) {
                throw new NbsFormatException(field, pos, "negative length: " + length);
            }
            if (length > data.length - pos) {
                throw new NbsFormatException(field, pos,
                        "unexpected end of file, wanted " + length + " bytes, "
                                + (data.length - pos) + " remain");
            }
            String value = new String(data, pos, length, StandardCharsets.ISO_8859_1);
            pos += length;
            return value;
        }
    }
}
