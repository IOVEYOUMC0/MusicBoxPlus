package com.huidu.musicboxplus.core.song;

// Thrown when a referenced song turns out not to exist, so callers can fail fast
// instead of threading null checks through the whole playback call chain.
public class SongNullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static SongNullException forHash(int hash) {
        return new SongNullException("No song registered under hash " + hash);
    }

    public static SongNullException forName(String name) {
        return new SongNullException("No song named \"" + name + "\" is registered");
    }

    public SongNullException(String message) {
        super(message);
    }

    public SongNullException(String message, Throwable cause) {
        super(message, cause);
    }
}