package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NotePitchMapperTest {

    @Test
    void nbsKeyMapsToEditorPitchRelativeToMinecraftRange() {
        assertEquals(0, NotePitchMapper.nbsKeyToEditorPitch(NotePitchMapper.MINECRAFT_MIN_NBS_KEY));
        assertEquals(24, NotePitchMapper.nbsKeyToEditorPitch(NotePitchMapper.MINECRAFT_MAX_NBS_KEY));
    }

    @Test
    void nbsKeyOutsideRangeIsClamped() {
        assertEquals(0, NotePitchMapper.nbsKeyToEditorPitch(0));
        assertEquals(NotePitchMapper.MAX_EDITOR_PITCH, NotePitchMapper.nbsKeyToEditorPitch(1000));
    }

    @Test
    void editorPitchRoundTripsThroughNbsKey() {
        for (int pitch = 0; pitch <= 24; pitch++) {
            int nbsKey = NotePitchMapper.editorPitchToNbsKey(pitch) & 0xFF;
            assertEquals(pitch, NotePitchMapper.nbsKeyToEditorPitch(nbsKey), "pitch " + pitch);
        }
    }

    @Test
    void editorPitchToNbsPitchOffsetsByMinecraftMin() {
        NotePitchMapper.NbsPitch p = NotePitchMapper.editorPitchToNbsPitch(0);
        assertEquals((byte) NotePitchMapper.MINECRAFT_MIN_NBS_KEY, p.key());
        assertEquals((short) 0, p.finePitch());
    }

    @Test
    void bukkitNoteClampsToOneOctaveWhenTenOctaveDisabled() {
        assertEquals(0, NotePitchMapper.editorPitchToBukkitNote(0, false));
        assertEquals(24, NotePitchMapper.editorPitchToBukkitNote(24, false));
        assertEquals(24, NotePitchMapper.editorPitchToBukkitNote(80, false));
    }

    @Test
    void bukkitNoteWrapsHighPitchesWhenTenOctaveEnabled() {
        assertEquals(13, NotePitchMapper.editorPitchToBukkitNote(13, true));
        // 25 -> 13 (one octave down), stays within [0,24]
        assertEquals(13, NotePitchMapper.editorPitchToBukkitNote(25, true));
        assertEquals(24, NotePitchMapper.editorPitchToBukkitNote(24, true));
    }

    @Test
    void midiKeyMapsRelativeToA0() {
        // 21 (MIDI A0) + 33 (Minecraft min) == editor pitch 0
        assertEquals(0, NotePitchMapper.midiKeyToEditorPitch(21 + NotePitchMapper.MINECRAFT_MIN_NBS_KEY));
        assertEquals(0, NotePitchMapper.midiKeyToEditorPitch(0));
    }
}
