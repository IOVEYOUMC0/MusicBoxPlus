package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.core.sound.VanillaInstrument;
import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;
import org.bukkit.Instrument;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Locale;

// Plays preview note sounds for a single editing player: resource-pack sound first, then
// the vanilla note-block sound, then plain Bukkit note playing when neither is available.
final class MusicEditSoundPlayer {
    private final Player player;

    MusicEditSoundPlayer(Player player) {
        this.player = player;
    }

    void playNoteSound(int pitch, MusicNote.NoteInstrument instrument) {
        try {
            int effectivePitch = NotePitchMapper.editorPitchToBukkitNote(
                    pitch,
                    MusicBox.getInstance().getConfigObject().isEnable10octave()
            );

            float volume = getEditorPlaybackVolume();
            if (volume <= 0.0f) {
                return;
            }

            SoundCategory category = resolveEditorSoundCategory();
            if (ResourcePackInstrumentUtils.playCustomSound(player, effectivePitch, instrument, volume, category)) {
                return;
            }

            Sound sound = resolveEditorNoteSound(instrument);
            if (sound != null) {
                player.playSound(player.getLocation(), sound, category, volume, notePitch(effectivePitch));
            } else {
                Instrument bukkitInstrument = instrument.getBukkitInstrument();
                Note bukkitNote = new Note(effectivePitch);
                player.playNote(player.getLocation(), bukkitInstrument, bukkitNote);
            }
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().warning("播放音符失败: " + e.getMessage());
        }
    }

    private float getEditorPlaybackVolume() {
        return Math.max(0, Math.min(100, VolumeManager.getPlayerVolume(player))) / 100F * 3.0F;
    }

    private float notePitch(int bukkitNote) {
        return (float) Math.pow(2.0D, (Math.max(0, Math.min(24, bukkitNote)) - 12) / 12.0D);
    }

    private SoundCategory resolveEditorSoundCategory() {
        try {
            return SoundCategory.valueOf(MusicBox.getInstance().getConfigObject().getSoundCategory().toUpperCase());
        } catch (Exception ignored) {
            return SoundCategory.RECORDS;
        }
    }

    // The editor declares its instruments in NBS id order (PlayerMusicCompiler enforces that at
    // class load), so the shared table already holds the sound name. The switch this replaces
    // spelled out all sixteen names a second time and mapped the four trumpet timbres onto plain
    // trumpet, so the preview played a different sound than the note it was about to write.
    private Sound resolveEditorNoteSound(MusicNote.NoteInstrument instrument) {
        NamespacedKey key = NamespacedKey.fromString(
                VanillaInstrument.soundNameById(instrument.ordinal()));
        return key == null ? null : Registry.SOUND_EVENT.get(key);
    }
}
