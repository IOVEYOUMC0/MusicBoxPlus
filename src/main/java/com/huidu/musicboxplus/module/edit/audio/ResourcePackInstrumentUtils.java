package com.huidu.musicboxplus.module.edit.audio;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.core.nbs.RawNbsCustomInstrument;
import com.huidu.musicboxplus.core.sound.NotePitch;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.NotePitchMapper;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import org.bukkit.entity.Player;

import java.util.*;

public final class ResourcePackInstrumentUtils {

    private ResourcePackInstrumentUtils() {
    }

    public static boolean isConfiguredOverrideEnabled() {
        MusicBoxConfig config = getConfig();
        return config != null
                && config.getResourcePackInstruments() != null
                && config.getResourcePackInstruments().isEnabled();
    }

    public static String getConfiguredSoundKey(MusicNote.NoteInstrument instrument) {
        MusicBoxConfig config = getConfig();
        if (config == null || config.getResourcePackInstruments() == null) {
            return null;
        }
        Map<String, String> soundKeys = config.getResourcePackInstruments().getSoundKeys();
        if (soundKeys == null || soundKeys.isEmpty()) {
            return null;
        }

        String direct = soundKeys.get(instrument.name());
        if (isBlank(direct)) {
            direct = soundKeys.get(instrument.name().toLowerCase(Locale.ROOT));
        }
        if (isBlank(direct)) {
            direct = soundKeys.get(instrument.name().toUpperCase(Locale.ROOT));
        }
        return isBlank(direct) ? null : direct.trim();
    }

    public static boolean shouldUseCustomSound(MusicNote.NoteInstrument instrument) {
        if (instrument == null) {
            return false;
        }
        return isConfiguredOverrideEnabled() && getConfiguredSoundKey(instrument) != null;
    }

    public static String resolveSoundKey(MusicNote.NoteInstrument instrument) {
        if (instrument == null) {
            return null;
        }
        return getConfiguredSoundKey(instrument);
    }

    public static boolean playCustomSound(Player player, int effectivePitch, MusicNote.NoteInstrument instrument,
                                          float volume) {
        return playCustomSound(player, effectivePitch, instrument, volume, org.bukkit.SoundCategory.MASTER);
    }

    // category comes from the caller rather than being fixed here, so a resource-pack instrument
    // lands on the same volume slider as every other note. Pinning it to MASTER made this the one
    // playback path that ignored the configured soundCategory.
    public static boolean playCustomSound(Player player, int effectivePitch, MusicNote.NoteInstrument instrument,
                                          float volume, org.bukkit.SoundCategory category) {
        String soundKey = resolveSoundKey(instrument);
        if (player == null || soundKey == null || soundKey.isBlank()) {
            return false;
        }
        if (volume <= 0.0f) {
            return true;
        }

        byte nbsKey = NotePitchMapper.editorPitchToNbsKey(effectivePitch);
        float soundPitch = NotePitch.transposedPitch(nbsKey, 0);
        player.playSound(player.getLocation(), soundKey, category, volume, soundPitch);
        return true;
    }

    // Custom instrument table for exporting player-made music: every instrument that resolves to
    // a resource-pack sound gets an entry, numbered from firstCustomInstrumentIndex on.
    public static CustomInstrumentData buildCustomInstrumentData(PlayerMusic music, byte firstCustomInstrumentIndex) {
        Map<MusicNote.NoteInstrument, Byte> indexMap = new EnumMap<>(MusicNote.NoteInstrument.class);
        List<RawNbsCustomInstrument> customInstruments = new ArrayList<>();

        for (MusicNote note : music.getNotesSortedByTick()) {
            for (MusicNote.NoteInstrument instrument : note.getInstruments()) {
                if (!shouldUseCustomSound(instrument) || indexMap.containsKey(instrument)) {
                    continue;
                }

                String soundKey = resolveSoundKey(instrument);
                if (soundKey == null || soundKey.isBlank()) {
                    continue;
                }

                byte relativeIndex = (byte) indexMap.size();
                indexMap.put(instrument, (byte) (firstCustomInstrumentIndex + relativeIndex));
                customInstruments.add(new RawNbsCustomInstrument(
                        instrument.name(), soundKey + ".ogg", DEFAULT_CUSTOM_INSTRUMENT_PITCH, false));
            }
        }

        return new CustomInstrumentData(List.copyOf(customInstruments), indexMap);
    }

    // Replacement sounds for vanilla instrument ids, empty when no override applies.
    //
    // This is the whole of the override for the engine that reads a compiled song: the id keeps
    // its meaning and only the sound behind it changes, so nothing has to renumber instruments or
    // copy the note graph.
    public static Map<Integer, String> buildSoundOverrides() {
        if (!isConfiguredOverrideEnabled()) {
            return Map.of();
        }
        Map<Integer, String> overrides = new HashMap<>();
        for (int instrumentId = 0; instrumentId < NBS_VANILLA_INSTRUMENT_IDS; instrumentId++) {
            MusicNote.NoteInstrument instrument = mapNbsInstrument(instrumentId);
            if (!shouldUseCustomSound(instrument)) {
                continue;
            }
            String soundKey = resolveSoundKey(instrument);
            if (soundKey != null && !soundKey.isBlank()) {
                overrides.put(instrumentId, soundKey);
            }
        }
        return overrides;
    }

    // Vanilla instrument ids mapNbsInstrument is defined over.
    private static final int NBS_VANILLA_INSTRUMENT_IDS = 20;

    // Sound key a custom instrument sample is recorded at; 45 is the format's default.
    private static final int DEFAULT_CUSTOM_INSTRUMENT_PITCH = 45;

    private static MusicBoxConfig getConfig() {
        try {
            MusicBox musicBox = MusicBox.getInstance();
            return musicBox != null ? musicBox.getConfigObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static MusicNote.NoteInstrument mapNbsInstrument(int nbsInstrument) {
        return switch (nbsInstrument) {
            case 0 -> MusicNote.NoteInstrument.HARP;
            case 1 -> MusicNote.NoteInstrument.BASS;
            case 2 -> MusicNote.NoteInstrument.BASS_DRUM;
            case 3 -> MusicNote.NoteInstrument.SNARE_DRUM;
            case 4 -> MusicNote.NoteInstrument.CLICKS;
            case 5 -> MusicNote.NoteInstrument.GUITAR;
            case 6 -> MusicNote.NoteInstrument.FLUTE;
            case 7 -> MusicNote.NoteInstrument.BELL;
            case 8 -> MusicNote.NoteInstrument.CHIME;
            case 9 -> MusicNote.NoteInstrument.XYLOPHONE;
            case 10 -> MusicNote.NoteInstrument.IRON_XYLOPHONE;
            case 11 -> MusicNote.NoteInstrument.COW_BELL;
            case 12 -> MusicNote.NoteInstrument.DIDGERIDOO;
            case 13 -> MusicNote.NoteInstrument.BIT;
            case 14 -> MusicNote.NoteInstrument.BANJO;
            case 15 -> MusicNote.NoteInstrument.PLING;
            case 16 -> MusicNote.NoteInstrument.TRUMPET;
            case 17 -> MusicNote.NoteInstrument.TRUMPET_EXPOSED;
            case 18 -> MusicNote.NoteInstrument.TRUMPET_WEATHERED;
            case 19 -> MusicNote.NoteInstrument.TRUMPET_OXIDIZED;
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record CustomInstrumentData(List<RawNbsCustomInstrument> customInstruments, Map<MusicNote.NoteInstrument, Byte> instrumentIndexMap) {
    }
}
