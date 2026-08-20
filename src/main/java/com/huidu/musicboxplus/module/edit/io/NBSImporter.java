package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.DebugLogger;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import com.huidu.musicboxplus.core.nbs.RawNbsNote;
import com.huidu.musicboxplus.core.nbs.RawNbsSong;
import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.NotePitchMapper;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public class NBSImporter {

    private static final NBSImporter INSTANCE = new NBSImporter();
    private static final int[] SUBDIVISION_CANDIDATES = new int[]{16, 12, 8, 6, 4, 3, 2, 1};

    public static NBSImporter getInstance() {
        return INSTANCE;
    }

    private NBSImporter() {
    }

    public PlayerMusic importFromFile(File file, Player player) throws IOException {
        return importFromFile(file, player.getName(), player.getUniqueId());
    }

    public PlayerMusic importFromFile(File file, String author, UUID authorUUID) throws IOException {
        return convertToPlayerMusic(NbsReader.read(file.toPath()), author, authorUUID, file.getName());
    }

    public PlayerMusic importFromStream(InputStream stream, String fileName, Player player) throws IOException {
        return importFromStream(stream, fileName, player.getName(), player.getUniqueId());
    }

    public PlayerMusic importFromStream(InputStream stream, String fileName, String author, UUID authorUUID) throws IOException {
        return convertToPlayerMusic(NbsReader.read(stream), author, authorUUID, fileName);
    }

    private PlayerMusic convertToPlayerMusic(RawNbsSong song, String author, UUID authorUUID, String fileName) throws IOException {
        String name = song.title();
        if (name == null || name.isEmpty()) {
            name = fileName.replaceAll("\\.[^.]+$", "");
        }

        PlayerMusic music = new PlayerMusic(name, author, authorUUID);

        float speed = song.ticksPerSecond();
        TempoMapping tempoMapping = chooseTempoMapping(speed);
        music.setBpm(tempoMapping.bpm());
        music.setBeatSubdivision(tempoMapping.beatSubdivision());

        String description = song.description();
        if (description != null && !description.isEmpty()) {
            music.setDescription(description);
        }

        for (RawNbsNote nbsNote : song.notes()) {
            int tick = nbsNote.tick();
            int pitch = NotePitchMapper.nbsKeyToEditorPitch(nbsNote.key(), (short) nbsNote.finePitch());
            MusicNote.NoteInstrument instrument = convertNBSInstrument(nbsNote.instrument());

            MusicNote existingNote = music.getNote(pitch, tick);
            if (existingNote == null) {
                MusicNote newNote = new MusicNote(pitch, tick);
                newNote.addInstrument(instrument);
                music.addNote(newNote);
            } else if (!existingNote.getInstruments().contains(instrument)) {
                existingNote.addInstrument(instrument);
            }
        }

        if (music.getNoteCount() > MusicFileImporter.MAX_IMPORT_NOTES) {
            throw new IOException("Imported file has too many notes ("
                    + music.getNoteCount() + " > " + MusicFileImporter.MAX_IMPORT_NOTES + ")");
        }
        if (!PlayerMusicManager.getInstance().saveMusicSync(music)) {
            throw new IllegalStateException("Failed to save imported music");
        }
        DebugLogger.debug("NBS import completed: " + name + ", notes: " + music.getNoteCount()
                + ", BPM: " + tempoMapping.bpm()
                + ", subdivision: " + tempoMapping.beatSubdivision()
                + ", source speed: " + speed);

        return music;
    }

    private TempoMapping chooseTempoMapping(float speed) {
        double targetTicksPerSecond = Math.max(0.1d, speed);
        int minBpm = getMinBpm();
        int maxBpm = getMaxBpm();

        TempoMapping best = new TempoMapping(Math.max(minBpm, Math.min(maxBpm, Math.round((float) (targetTicksPerSecond * 60.0d / 4.0d)))), 4);
        double bestError = Math.abs(toTicksPerSecond(best) - targetTicksPerSecond);

        for (int subdivision : SUBDIVISION_CANDIDATES) {
            int bpm = Math.max(minBpm, Math.min(maxBpm, (int) Math.round(targetTicksPerSecond * 60.0d / subdivision)));
            TempoMapping candidate = new TempoMapping(bpm, subdivision);
            double error = Math.abs(toTicksPerSecond(candidate) - targetTicksPerSecond);
            if (error < bestError || (Math.abs(error - bestError) < 0.000001d && subdivision == 4)) {
                best = candidate;
                bestError = error;
            }
        }

        return best;
    }

    private double toTicksPerSecond(TempoMapping mapping) {
        return mapping.bpm() * mapping.beatSubdivision() / 60.0d;
    }

    private int getMinBpm() {
        try {
            MusicBox musicBox = MusicBox.getInstance();
            return musicBox != null && musicBox.getConfigObject() != null && musicBox.getConfigObject().getEditor() != null
                    ? musicBox.getConfigObject().getEditor().getMinBpm()
                    : 20;
        } catch (RuntimeException ignored) {
            return 20;
        }
    }

    private int getMaxBpm() {
        try {
            MusicBox musicBox = MusicBox.getInstance();
            return musicBox != null && musicBox.getConfigObject() != null && musicBox.getConfigObject().getEditor() != null
                    ? musicBox.getConfigObject().getEditor().getMaxBpm()
                    : 300;
        } catch (RuntimeException ignored) {
            return 300;
        }
    }

    private MusicNote.NoteInstrument convertNBSInstrument(int nbsInstrument) {
        switch (nbsInstrument) {
            case 1:
                return MusicNote.NoteInstrument.BASS;
            case 2:
                return MusicNote.NoteInstrument.BASS_DRUM;
            case 3:
                return MusicNote.NoteInstrument.SNARE_DRUM;
            case 4:
                return MusicNote.NoteInstrument.CLICKS;
            case 5:
                return MusicNote.NoteInstrument.GUITAR;
            case 6:
                return MusicNote.NoteInstrument.FLUTE;
            case 7:
                return MusicNote.NoteInstrument.BELL;
            case 8:
                return MusicNote.NoteInstrument.CHIME;
            case 9:
                return MusicNote.NoteInstrument.XYLOPHONE;
            case 10:
                return MusicNote.NoteInstrument.IRON_XYLOPHONE;
            case 11:
                return MusicNote.NoteInstrument.COW_BELL;
            case 12:
                return MusicNote.NoteInstrument.DIDGERIDOO;
            case 13:
                return MusicNote.NoteInstrument.BIT;
            case 14:
                return MusicNote.NoteInstrument.BANJO;
            case 15:
                return MusicNote.NoteInstrument.PLING;
            case 16:
                return MusicNote.NoteInstrument.normalizeForCurrentConfig(MusicNote.NoteInstrument.TRUMPET);
            case 17:
                return MusicNote.NoteInstrument.normalizeForCurrentConfig(MusicNote.NoteInstrument.TRUMPET_EXPOSED);
            case 18:
                return MusicNote.NoteInstrument.normalizeForCurrentConfig(MusicNote.NoteInstrument.TRUMPET_WEATHERED);
            case 19:
                return MusicNote.NoteInstrument.normalizeForCurrentConfig(MusicNote.NoteInstrument.TRUMPET_OXIDIZED);
            default:
                return MusicNote.NoteInstrument.HARP;
        }
    }

    private record TempoMapping(int bpm, int beatSubdivision) {
    }
}

