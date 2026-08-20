package com.huidu.musicboxplus.core.player.loop;

import com.huidu.musicboxplus.api.player.loop.LoopMode;

// What happens when a song reaches its end.
//
// Looping and playlists are MusicBox's own: the playback engine only reports that a song
// finished, and this decides the rest. Keeping the decision here, separate from the player it
// acts on, means the rules can be checked as a table rather than only by playing songs on a
// server.
//
// The order the cases are tried in is the behaviour, not an implementation detail:
// repeat-one wins over advancing, advancing wins over wrapping, and a player that must outlive
// its song wins over stopping.
public enum SongEndAction {

    // Repeat-one: rewind to the start and keep playing the same song.
    REPLAY_CURRENT,

    // The playlist has another song; advance and hand over to a fresh player.
    ADVANCE_NEXT,

    // Repeat-all: the playlist ran out, so wrap to the first song.
    RESTART_FIRST,

    // Stop on the final frame but stay alive. Placed displays use this: destroying them would
    // take their floating text with it, permanently.
    HOLD_AT_END,

    // Nothing left to play and nothing to preserve. This is the only normal termination, and the
    // only one that runs songEnd() -- the sign's redstone pulse. A jukebox keeps its disc: see
    // JukeboxPlayer.songEnd().
    STOP;

    // canReplay is false when there is no player to rewind, in which case repeat-one cannot
    // apply and the remaining rules decide.
    public static SongEndAction decide(LoopMode mode, boolean hasNext, boolean canReplay,
                                       boolean persistentOnEnd) {
        if (mode == LoopMode.SINGLE && canReplay) {
            return REPLAY_CURRENT;
        }
        if (hasNext) {
            return ADVANCE_NEXT;
        }
        if (mode == LoopMode.ALL) {
            return RESTART_FIRST;
        }
        if (persistentOnEnd) {
            return HOLD_AT_END;
        }
        return STOP;
    }
}
