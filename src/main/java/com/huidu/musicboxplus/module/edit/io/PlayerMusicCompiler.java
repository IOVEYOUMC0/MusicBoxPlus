package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.nbs.RawNbsLayer;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.NotePitchMapper;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Arranges music written in the in-game editor for playback, without building an NBS song on
// the way. That route still exists for export, in NoteBlockSongConverter.
//
// Two things the file-shaped route needs and this one does not:
//
// Layers. An NBS layer holds one note per tick, so a chord has to be split across as many
// layers as it has notes, and the converter carries a per-instrument layer registry to place
// them. Here the notes of a tick are simply consecutive entries, so a chord needs no
// bookkeeping at all and everything sits on one layer.
//
// Custom instruments. Substituting a resource-pack sound there means appending custom
// instruments and pointing notes at them by index. Here it is a name substitution in the
// instrument table, the same one file-backed songs use, so the editor and the file path share
// one mechanism instead of two.
public final class PlayerMusicCompiler {

    // Editor instruments are declared in NBS instrument order, so the ordinal is the id. The
    // check below fails at class load if that ever stops holding.
    private static final int[] NBS_ID_BY_ORDINAL;

    static {
        MusicNote.NoteInstrument[] values = MusicNote.NoteInstrument.values();
        NBS_ID_BY_ORDINAL = new int[values.length];
        for (int id = 0; id < values.length; id++) {
            MusicNote.NoteInstrument expected = ResourcePackInstrumentUtils.mapNbsInstrument(id);
            if (expected != null && expected != values[id]) {
                throw new IllegalStateException("editor instrument order no longer matches NBS ids: "
                        + "id " + id + " is " + expected + " but ordinal " + id + " is " + values[id]);
            }
            NBS_ID_BY_ORDINAL[id] = id;
        }
    }

    private static final int NEUTRAL = 100;

    private PlayerMusicCompiler() {
    }

    public static CompiledSong compile(PlayerMusic music) {
        return compile(music, ResourcePackInstrumentUtils.buildSoundOverrides());
    }

    public static CompiledSong compile(PlayerMusic music, Map<Integer, String> soundOverrides) {
        if (music == null) {
            return null;
        }

        List<RawNbsNote> notes = new ArrayList<>();
        int maxTick = 0;
        for (MusicNote musicNote : music.getNotesSortedByTick()) {
            int tick = Math.max(0, musicNote.getTick());
            maxTick = Math.max(maxTick, tick);
            NotePitchMapper.NbsPitch pitch = NotePitchMapper.editorPitchToNbsPitch(musicNote.getPitch());
            for (MusicNote.NoteInstrument instrument : musicNote.getInstruments()) {
                if (instrument == null) {
                    continue;
                }
                notes.add(new RawNbsNote(tick, 0, NBS_ID_BY_ORDINAL[instrument.ordinal()],
                        pitch.key() & 0xFF, NEUTRAL, NEUTRAL, pitch.finePitch()));
            }
        }

        List<RawNbsLayer> layers = List.of(new RawNbsLayer(0, "", false, NEUTRAL, NEUTRAL));
        int tempoRaw = Math.round(Math.max(0.1f,
                music.getBpm() * music.getBeatSubdivision() / 60.0f) * 100f);

        RawNbsSong raw = new RawNbsSong(5, MusicNote.NoteInstrument.values().length, maxTick,
                layers.size(), music.getName(), music.getAuthor(), music.getAuthor(),
                music.getDescription(), tempoRaw, 4, false, 0, 0,
                notes, layers, List.of());
        return CompiledSong.compile(raw, soundOverrides);
    }
}
