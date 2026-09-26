package com.huidu.musicboxplus.core.sound;

import com.huidu.musicboxplus.core.nbs.RawNbsCustomInstrument;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;

import java.util.List;
import java.util.Locale;
import java.util.Map;

// Instrument id -> sound name lookup table for a single song. Built once after parsing;
// playback then only does array indexing.
//
// NBS numbers vanilla and custom instruments in one continuous id space: ids below the
// header's vanillaInstrumentCount are vanilla, the rest minus that value index into the
// custom instrument table. The split follows the count the file itself declares, never how
// many vanilla instruments the running server happens to have.
//
// The table is expanded to [instrument][bucket] with the 5 suffix variants (see the
// bucketing in NotePitch), so resolving a name during playback is two array reads: no
// string concatenation, no hash lookup, no allocation. This path runs once per note, per
// listener, per tick.
//
// Has no server dependency, so it is fully unit-testable.
public final class SongInstruments {

    // Must stay in sync with the number of pitch suffix buckets
    private static final int VARIANT_COUNT = 5;

    private static final String OGG_SUFFIX = ".ogg";

    // A custom instrument that merely points at the vanilla pling sample keeps using the
    // vanilla sound, so no resource pack is required.
    private static final String PLING_SOUND = VanillaInstrument.PLING.soundName();

    // Sound names for an instrument id the file never declared, which can only come from a
    // corrupt file. Built once: falling back by constructing the row on the spot would allocate
    // an array and five concatenated strings for every note of every listener, twenty times a
    // second, for as long as that song plays.
    private static final String[] FALLBACK_VARIANTS = buildVariants(VanillaInstrument.HARP.soundName());

    private final String[][] variants;
    // Whether an id still resolves to its stock vanilla sound, i.e. it is not a custom
    // instrument and no resource-pack substitution replaced it. Those are the ids the server
    // can address by registry entry instead of by name.
    private final boolean[] plainVanilla;
    private final int vanillaCount;

    private SongInstruments(String[][] variants, boolean[] plainVanilla, int vanillaCount) {
        this.variants = variants;
        this.plainVanilla = plainVanilla;
        this.vanillaCount = vanillaCount;
    }

    public static SongInstruments of(RawNbsSong song) {
        return of(song.vanillaInstrumentCount(), song.customInstruments(), Map.of());
    }

    // soundOverrides replaces the sound of a vanilla instrument id, which is how a resource
    // pack supplies instruments the server itself has no sound for.
    //
    // Substituting a name in this table is the whole operation. Reaching the same result
    // through the file's own instrument numbering means appending custom instruments and
    // rewriting the instrument id of every note, which costs a full copy of the layer and note
    // graph and leaves two numbering bases that have to agree.
    public static SongInstruments of(RawNbsSong song, Map<Integer, String> soundOverrides) {
        return of(song.vanillaInstrumentCount(), song.customInstruments(), soundOverrides);
    }

    public static SongInstruments of(int fileVanillaCount, List<RawNbsCustomInstrument> customInstruments) {
        return of(fileVanillaCount, customInstruments, Map.of());
    }

    public static SongInstruments of(int fileVanillaCount,
                                     List<RawNbsCustomInstrument> customInstruments,
                                     Map<Integer, String> soundOverrides) {
        int vanilla = Math.max(0, fileVanillaCount);
        int total = vanilla + customInstruments.size();
        String[][] table = new String[total][];
        boolean[] plain = new boolean[total];
        for (int id = 0; id < total; id++) {
            String base;
            String override = id < vanilla ? soundOverrides.get(id) : null;
            if (override != null && !override.isBlank()) {
                base = override;
            } else if (id < vanilla) {
                base = VanillaInstrument.soundNameById(id);
                plain[id] = true;
            } else {
                base = customSoundName(customInstruments.get(id - vanilla));
            }
            table[id] = buildVariants(base);
        }
        return new SongInstruments(table, plain, vanilla);
    }

    // Sound name for transpose mode: no bucket suffix.
    public String baseSoundName(int instrumentId) {
        return soundName(instrumentId, NotePitch.NATURAL_BUCKET);
    }

    // Sound name for bucketed mode; bucket comes from NotePitch.bucketIndex.
    public String soundName(int instrumentId, int bucket) {
        String[] row = instrumentId >= 0 && instrumentId < variants.length
                ? variants[instrumentId]
                : FALLBACK_VARIANTS;
        return row[Math.max(0, Math.min(VARIANT_COUNT - 1, bucket))];
    }

    // True when this id plays a stock vanilla instrument under its own name. Callers that can
    // address sounds by registry entry use this to skip the name-based path, which makes the
    // server parse an identifier and allocate a one-off sound event for every note.
    public boolean isPlainVanilla(int instrumentId) {
        return instrumentId >= 0 && instrumentId < plainVanilla.length && plainVanilla[instrumentId];
    }

    public boolean isCustom(int instrumentId) {
        return instrumentId >= vanillaCount;
    }

    // Instruments with an empty sound name must not send a packet.
    //
    // Such instruments really do occur in files: Note Block Studio carries tempo changes on
    // a custom instrument with an empty sound file (usually named "Tempo Changer", with the
    // new speed encoded in the note's finePitch, divided by 15 for t/s), and unused
    // placeholder instruments are left blank too. Neither is meant to be audible, and
    // handing an empty name to playSound sends every listener a useless sound packet.
    public boolean isSilent(int instrumentId) {
        return soundName(instrumentId, NotePitch.NATURAL_BUCKET).isEmpty();
    }

    public int size() {
        return variants.length;
    }

    private static String[] buildVariants(String base) {
        String[] row = new String[VARIANT_COUNT];
        for (int bucket = 0; bucket < VARIANT_COUNT; bucket++) {
            row[bucket] = base + NotePitch.bucketSuffix(bucket);
        }
        return row;
    }

    // The file stores the resource pack file name; the sound name is that without the
    // extension. Strip the literal suffix -- replaceAll(".ogg", "") would treat the dot as a
    // regex wildcard and swallow names like "logg.ogg" whole.
    private static String customSoundName(RawNbsCustomInstrument instrument) {
        String name = instrument.soundFile() == null ? "" : instrument.soundFile();
        if (name.length() > OGG_SUFFIX.length()
                && name.regionMatches(true, name.length() - OGG_SUFFIX.length(),
                                      OGG_SUFFIX, 0, OGG_SUFFIX.length())) {
            name = name.substring(0, name.length() - OGG_SUFFIX.length());
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("pling") || lower.equals("block.note_block.pling")) {
            return PLING_SOUND;
        }
        return name;
    }
}
