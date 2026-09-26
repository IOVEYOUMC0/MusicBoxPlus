package com.huidu.musicboxplus.module.edit;

public final class NotePitchMapper {
    public static final int MIN_NBS_KEY = 0;
    public static final int MAX_NBS_KEY = 87;
    public static final int MINECRAFT_MIN_NBS_KEY = 33;
    public static final int MINECRAFT_MAX_NBS_KEY = 57;
    public static final int MIN_EDITOR_PITCH = 0;
    public static final int MAX_EDITOR_PITCH = 119;

    private NotePitchMapper() {
    }

    public static int nbsKeyToEditorPitch(int nbsKey) {
        return nbsKeyToEditorPitch(nbsKey, (short) 0);
    }

    public static int nbsKeyToEditorPitch(int nbsKey, short finePitch) {
        int effectiveKey = Math.round(nbsKey + finePitch / 100.0f);
        return clamp(effectiveKey - MINECRAFT_MIN_NBS_KEY, MIN_EDITOR_PITCH, MAX_EDITOR_PITCH);
    }

    public static byte editorPitchToNbsKey(int editorPitch) {
        return editorPitchToNbsPitch(editorPitch).key();
    }

    public static NbsPitch editorPitchToNbsPitch(int editorPitch) {
        int desiredKey = clamp(editorPitch, MIN_EDITOR_PITCH, MAX_EDITOR_PITCH) + MINECRAFT_MIN_NBS_KEY;
        int nbsKey = clamp(desiredKey, MIN_NBS_KEY, MAX_NBS_KEY);
        int finePitch = (desiredKey - nbsKey) * 100;
        return new NbsPitch((byte) nbsKey, (short) finePitch);
    }

    public static int midiKeyToEditorPitch(int midiKey) {
        // NBS stores MIDI A0 (21) as key 0, while editor pitch 0 is Minecraft key 33.
        return clamp(midiKey - (21 + MINECRAFT_MIN_NBS_KEY), MIN_EDITOR_PITCH, MAX_EDITOR_PITCH);
    }

    public static int editorPitchToBukkitNote(int editorPitch, boolean enable10Octave) {
        int effectivePitch = clamp(editorPitch, MIN_EDITOR_PITCH, MAX_EDITOR_PITCH);
        if (!enable10Octave) {
            return clamp(effectivePitch, MIN_EDITOR_PITCH, 24);
        }
        while (effectivePitch > 24) {
            effectivePitch -= 12;
        }
        return clamp(effectivePitch, MIN_EDITOR_PITCH, 24);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record NbsPitch(byte key, short finePitch) {
    }
}
