package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.core.nbs.RawNbsLayer;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import com.huidu.musicboxplus.core.sound.VanillaInstrument;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.NotePitchMapper;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Editor music -> raw NBS song. Only the export side uses this; playback compiles the same
// PlayerMusic straight into an arrangement through PlayerMusicCompiler.
public final class NoteBlockSongConverter {

    private static final int DEFAULT_VELOCITY = 100;
    private static final int CENTERED_PANNING = 100;

    private NoteBlockSongConverter() {
    }

    public static ConversionResult fromPlayerMusic(PlayerMusic music) {
        List<RawNbsNote> notes = new ArrayList<>();
        List<RawNbsLayer> layers = new ArrayList<>();
        // Per instrument, the layers already opened for it, in creation order. Chords put the
        // repeat of an instrument on a further layer, because one layer holds a single note
        // per tick.
        Map<MusicNote.NoteInstrument, List<Integer>> instrumentLayers = new HashMap<>();
        // Ticks already taken on a given layer, so a chord can find one that is still free.
        List<java.util.Set<Integer>> occupiedTicks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int vanillaInstrumentCount = VanillaInstrument.count();
        ResourcePackInstrumentUtils.CustomInstrumentData customInstrumentData =
                ResourcePackInstrumentUtils.buildCustomInstrumentData(music, (byte) vanillaInstrumentCount);

        for (MusicNote musicNote : music.getNotesSortedByTick()) {
            int tick = Math.max(0, musicNote.getTick());
            NotePitchMapper.NbsPitch nbsPitch = convertPitchToNbsPitch(musicNote.getPitch(), warnings);

            for (MusicNote.NoteInstrument instrument : musicNote.getInstruments()) {
                int layerIndex = findOrCreateLayer(layers, occupiedTicks, instrumentLayers, instrument, tick);
                occupiedTicks.get(layerIndex).add(tick);
                notes.add(new RawNbsNote(
                        tick,
                        layerIndex,
                        toNbsInstrument(instrument, customInstrumentData.instrumentIndexMap()),
                        nbsPitch.key(),
                        DEFAULT_VELOCITY,
                        CENTERED_PANNING,
                        nbsPitch.finePitch()
                ));
            }
        }

        int songHeight = Math.max(1, layers.size());
        int lengthTicks = Math.max(1, music.getMaxTick() + 1);
        float ticksPerSecond = Math.max(0.1f, music.getBpm() * music.getBeatSubdivision() / 60.0f);
        int timeSignature = Math.max(1, Math.min(16, music.getTimeSignature().getBeatsPerMeasure()));

        RawNbsSong song = new RawNbsSong(
                4,
                vanillaInstrumentCount,
                lengthTicks,
                songHeight,
                music.getName(),
                music.getAuthor(),
                music.getAuthor(),
                music.getDescription(),
                Math.round(ticksPerSecond * 100f),
                timeSignature,
                false,
                0,
                0,
                List.copyOf(notes),
                List.copyOf(layers),
                customInstrumentData.customInstruments()
        );
        return new ConversionResult(song, warnings);
    }

    private static int findOrCreateLayer(List<RawNbsLayer> layers,
                                         List<java.util.Set<Integer>> occupiedTicks,
                                         Map<MusicNote.NoteInstrument, List<Integer>> instrumentLayers,
                                         MusicNote.NoteInstrument instrument,
                                         int tick) {
        List<Integer> existingLayers = instrumentLayers.computeIfAbsent(instrument, ignored -> new ArrayList<>());
        for (Integer layerIndex : existingLayers) {
            if (!occupiedTicks.get(layerIndex).contains(tick)) {
                return layerIndex;
            }
        }

        int layerIndex = layers.size();
        layers.add(new RawNbsLayer(layerIndex, instrument.name(), false, DEFAULT_VELOCITY, CENTERED_PANNING));
        occupiedTicks.add(new java.util.HashSet<>());
        existingLayers.add(layerIndex);
        return layerIndex;
    }

    private static NotePitchMapper.NbsPitch convertPitchToNbsPitch(int pitch, List<String> warnings) {
        int key = pitch + NotePitchMapper.MINECRAFT_MIN_NBS_KEY;
        if (key < NotePitchMapper.MIN_NBS_KEY) {
            warnings.add("pitch_clamped_low");
        } else if (key > NotePitchMapper.MAX_NBS_KEY && pitch > NotePitchMapper.MAX_EDITOR_PITCH) {
            warnings.add("pitch_clamped_high");
        }
        return NotePitchMapper.editorPitchToNbsPitch(pitch);
    }

    private static int toNbsInstrument(MusicNote.NoteInstrument instrument, Map<MusicNote.NoteInstrument, Byte> customInstrumentMap) {
        Byte customIndex = customInstrumentMap.get(instrument);
        if (customIndex != null) {
            return customIndex & 0xFF;
        }
        return switch (instrument) {
            case HARP -> 0;
            case BASS -> 1;
            case BASS_DRUM -> 2;
            case SNARE_DRUM -> 3;
            case CLICKS -> 4;
            case GUITAR -> 5;
            case FLUTE -> 6;
            case BELL -> 7;
            case CHIME -> 8;
            case XYLOPHONE -> 9;
            case IRON_XYLOPHONE -> 10;
            case COW_BELL -> 11;
            case DIDGERIDOO -> 12;
            case BIT -> 13;
            case BANJO -> 14;
            case PLING -> 15;
            case TRUMPET -> 16;
            case TRUMPET_EXPOSED -> 17;
            case TRUMPET_WEATHERED -> 18;
            case TRUMPET_OXIDIZED -> 19;
        };
    }

    public record ConversionResult(RawNbsSong song, List<String> warnings) {
    }
}
