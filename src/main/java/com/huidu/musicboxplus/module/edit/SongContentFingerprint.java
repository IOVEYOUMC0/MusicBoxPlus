package com.huidu.musicboxplus.module.edit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, length-delimited content digests for persistence deduplication (not object hashCode). */
record SongContentFingerprint(String song, String notes) {
    static SongContentFingerprint of(PlayerMusic music) {
        MessageDigest noteDigest = digest();
        putInt(noteDigest, music.getNoteCount());
        music.forEachNote(note -> {
            putInt(noteDigest, note.getPitch());
            putInt(noteDigest, note.getTick());
            var instruments = note.getInstruments();
            putInt(noteDigest, instruments.size());
            for (var instrument : instruments) {
                // Digests are retained only in this process, never persisted between plugin builds.
                // Ordinals avoid UTF-8 byte arrays per note/instrument on every save.
                putInt(noteDigest, instrument.ordinal());
            }
        });
        byte[] notes = noteDigest.digest();
        MessageDigest songDigest = digest();
        putString(songDigest, music.getUniqueId().toString());
        putString(songDigest, music.getName());
        putString(songDigest, music.getAuthor());
        putString(songDigest, music.getAuthorUUID().toString());
        putString(songDigest, music.getTimeSignature().name());
        putInt(songDigest, music.getBpm());
        putInt(songDigest, music.getBeatSubdivision());
        putString(songDigest, music.getDescription());
        long created = music.getCreatedAt();
        putInt(songDigest, (int) (created >>> 32));
        putInt(songDigest, (int) created);
        songDigest.update(notes);
        return new SongContentFingerprint(HexFormat.of().formatHex(songDigest.digest()),
                HexFormat.of().formatHex(notes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime is missing SHA-256", impossible);
        }
    }

    private static void putString(MessageDigest digest, String value) {
        if (value == null) {
            putInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
