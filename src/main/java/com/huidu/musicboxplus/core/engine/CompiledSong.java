package com.huidu.musicboxplus.core.engine;

import com.huidu.musicboxplus.core.nbs.RawNbsLayer;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import com.huidu.musicboxplus.core.sound.SongInstruments;
import com.huidu.musicboxplus.core.sound.StereoPan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// A song arranged for playback: immutable, ordered by tick, and stored as parallel primitive
// arrays rather than an object graph.
//
// The file format groups notes by layer, while playback needs them by tick, so the ordering
// has to be inverted somewhere. Doing it here, once, at load time has three consequences
// worth stating:
//
// Playback speed stops affecting the arrangement. Speed belongs to the cursor that walks
// these ticks, not to the notes, so every speed setting shares one CompiledSong. Deriving a
// separate song object per speed multiplier instead means rebuilding a byte-for-byte
// identical arrangement for each one, and speed is player-controllable.
//
// Lifetime becomes the song's lifetime. There is no cache keyed on some other object, so
// nothing to evict, no idle timer, and nothing keeping a song strongly reachable after the
// owner has let go of it.
//
// The hot path becomes an array scan. Notes for tick t occupy the half-open range
// [noteStart(t), noteEnd(t)) in every per-note array, so reading a tick allocates nothing,
// boxes nothing, and chases no pointers.
//
// Layer volume and panning are denormalized onto each note. They are the only layer fields
// playback reads, and folding them in at compile time also settles a format quirk: a note may
// name a layer index at or beyond songHeight, which has no entry in the layer table. Those
// resolve to the neutral defaults here instead of needing a bounds check per note per
// listener.
//
// No dependency on the server: this is layout and arithmetic, so it stays unit-testable.
public final class CompiledSong {

    // Defaults for a note whose layer has no entry in the file's layer table.
    private static final int DEFAULT_LAYER_VOLUME = 100;
    private static final int DEFAULT_LAYER_PANNING = StereoPan.CENTER;

    // noteStart/noteEnd read from here; length is tickCount + 1 so the last tick has an end.
    private final int[] tickStart;

    // instrument/key/panning/layerPanning 在 NBS 格式中均为 u8（0-255），用 byte 存储，
    // getter 读取时 & 0xFF 还原无符号值。finePitch 是唯一的 i16 有符号字段，保留 short。
    private final byte[] instrument;
    private final byte[] key;
    private final byte[] velocity;
    private final byte[] panning;
    private final short[] finePitch;
    private final byte[] layerVolume;
    private final byte[] layerPanning;

    private final SongInstruments instruments;
    private final String title;
    private final String author;
    private final int lengthTicks;
    private final float ticksPerSecond;
    private final boolean stereo;

    private CompiledSong(int[] tickStart, byte[] instrument, byte[] key, byte[] velocity,
                         byte[] panning, short[] finePitch, byte[] layerVolume,
                         byte[] layerPanning, SongInstruments instruments, String title,
                         String author, int lengthTicks, float ticksPerSecond, boolean stereo) {
        this.tickStart = tickStart;
        this.instrument = instrument;
        this.key = key;
        this.velocity = velocity;
        this.panning = panning;
        this.finePitch = finePitch;
        this.layerVolume = layerVolume;
        this.layerPanning = layerPanning;
        this.instruments = instruments;
        this.title = title;
        this.author = author;
        this.lengthTicks = lengthTicks;
        this.ticksPerSecond = ticksPerSecond;
        this.stereo = stereo;
    }

    public static CompiledSong compile(RawNbsSong raw) {
        return compile(raw, Map.of());
    }

    // soundOverrides replaces the sound of a vanilla instrument id, for resource packs that
    // supply instruments this server has no sound for.
    public static CompiledSong compile(RawNbsSong raw, Map<Integer, String> soundOverrides) {
        List<RawNbsNote> notes = raw.notes();

        int maxTick = raw.lengthTicks();
        for (RawNbsNote note : notes) {
            if (note.tick() > maxTick) {
                maxTick = note.tick();
            }
        }
        // Playback runs the closed range [0, lengthTicks], so the arrangement needs one slot
        // past the last tick.
        int tickCount = Math.max(0, maxTick) + 1;

        int[] layerVolumeByIndex = new int[raw.layers().size()];
        int[] layerPanningByIndex = new int[raw.layers().size()];
        for (RawNbsLayer layer : raw.layers()) {
            if (layer.index() >= 0 && layer.index() < layerVolumeByIndex.length) {
                layerVolumeByIndex[layer.index()] = layer.volume();
                layerPanningByIndex[layer.index()] = layer.panning();
            }
        }

        // Counting sort by tick: one pass to size each bucket, a prefix sum, then one pass to
        // place. Keeps compile linear and the result ordered without sorting note objects.
        int[] tickStart = new int[tickCount + 1];
        for (RawNbsNote note : notes) {
            if (note.tick() >= 0 && note.tick() < tickCount) {
                tickStart[note.tick() + 1]++;
            }
        }
        for (int t = 0; t < tickCount; t++) {
            tickStart[t + 1] += tickStart[t];
        }

        int total = tickStart[tickCount];
        byte[] instrument = new byte[total];
        byte[] key = new byte[total];
        byte[] velocity = new byte[total];
        byte[] panning = new byte[total];
        short[] finePitch = new short[total];
        byte[] layerVolume = new byte[total];
        byte[] layerPanning = new byte[total];

        int[] cursor = tickStart.clone();
        boolean stereo = false;
        for (RawNbsNote note : notes) {
            if (note.tick() < 0 || note.tick() >= tickCount) {
                continue;
            }
            int layer = note.layer();
            int lv = layer >= 0 && layer < layerVolumeByIndex.length
                    ? layerVolumeByIndex[layer] : DEFAULT_LAYER_VOLUME;
            int lp = layer >= 0 && layer < layerPanningByIndex.length
                    ? layerPanningByIndex[layer] : DEFAULT_LAYER_PANNING;

            int i = cursor[note.tick()]++;
            instrument[i] = (byte) note.instrument();
            key[i] = (byte) note.key();
            velocity[i] = (byte) note.velocity();
            panning[i] = (byte) note.panning();
            finePitch[i] = (short) note.finePitch();
            layerVolume[i] = (byte) lv;
            layerPanning[i] = (byte) lp;

            if (!StereoPan.isCentered(lp, note.panning())) {
                stereo = true;
            }
        }

        return new CompiledSong(tickStart, instrument, key, velocity, panning, finePitch,
                layerVolume, layerPanning, SongInstruments.of(raw, soundOverrides), raw.title(),
                raw.author(), Math.max(0, raw.lengthTicks()), raw.ticksPerSecond(), stereo);
    }

    // Number of addressable ticks; valid ticks are 0..tickCount()-1.
    public int tickCount() {
        return tickStart.length - 1;
    }

    // Index of the first note at this tick. Out-of-range ticks report an empty span rather
    // than throwing, so a cursor running past the end simply plays nothing.
    public int noteStart(int tick) {
        return tick >= 0 && tick < tickCount() ? tickStart[tick] : 0;
    }

    public int noteEnd(int tick) {
        return tick >= 0 && tick < tickCount() ? tickStart[tick + 1] : 0;
    }

    public int totalNotes() {
        return instrument.length;
    }

    public int instrument(int note) {
        return instrument[note] & 0xFF;
    }

    public int key(int note) {
        return key[note] & 0xFF;
    }

    public int velocity(int note) {
        return velocity[note] & 0xFF;
    }

    public int panning(int note) {
        return panning[note] & 0xFF;
    }

    public int finePitch(int note) {
        return finePitch[note];
    }

    public int layerVolume(int note) {
        return layerVolume[note] & 0xFF;
    }

    public int layerPanning(int note) {
        return layerPanning[note] & 0xFF;
    }

    public SongInstruments instruments() {
        return instruments;
    }

    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    public int lengthTicks() {
        return lengthTicks;
    }

    // Song ticks per second at the song's own tempo, before any speed multiplier.
    public float ticksPerSecond() {
        return ticksPerSecond;
    }

    // Whether any note or layer is panned off center. A song without stereo information can
    // skip the fake-stereo path and the extra packet it costs per note per listener.
    public boolean isStereo() {
        return stereo;
    }

    // instrument/key/panning/layerVolume/layerPanning 都是 byte（5 个），finePitch 是 i16 保留 short。
    // 每音符：5 * byte + 1 * short = 7 字节。
    private static final int BYTES_PER_NOTE = 5 * Byte.BYTES + Short.BYTES;

    // Approximate retained size in bytes, for capacity planning. Counts the primitive arrays,
    // which dominate; object headers and the instrument name table are small and constant.
    public long approximateBytes() {
        return (long) tickStart.length * Integer.BYTES
                + (long) instrument.length * BYTES_PER_NOTE;
    }

}
