package com.huidu.musicboxplus.module.jukebox;

import java.util.function.Function;

/** Pins the current song of an already constructed playlist, including a disc pulled from a chest. */
record JukeboxStartGuard(int songHash) {
    static <P> JukeboxStartGuard capture(P playlist, Function<P, Integer> currentSongHash) {
        Integer hash = currentSongHash.apply(playlist);
        return hash == null ? null : new JukeboxStartGuard(hash);
    }

    boolean stillMatches(Integer liveSongHash) {
        // Null means absent/unrecognised. Every int, including -1, is a valid song identity.
        return liveSongHash != null && liveSongHash == songHash;
    }
}
