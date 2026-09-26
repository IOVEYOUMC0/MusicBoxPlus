package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.song.MusicBoxSong;

import java.util.function.Consumer;

// Values every player resolves once when it starts, rather than per note per listener.
//
// Shared by the block, speaker and radio players.
public final class PlaybackSetup {

    private PlaybackSetup() {
    }

    // Null when the song cannot be loaded, which leaves the player silent instead of failing to
    // construct: a jukebox or sign still exists and its other behaviour still has to work.
    public static CompiledSong compiledSongOf(IPlayList list) {
        try {
            MusicBoxSong current = list != null ? (MusicBoxSong) list.getCurrent() : null;
            return current != null ? current.getCompiledSong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Runs start once the current song's arrangement is in memory, so the player constructor --
    // which reaches compiledSongOf above -- cannot be the thing that reads and parses the file.
    //
    // Building the arrangement is a whole-file read plus a parse of every note: about 1 ms for an
    // average song and 16 ms for the largest in a 279-song library, which is a third of a tick
    // spent on whichever thread handled the click. Small, but it lands on the main thread and it
    // recurs, because the per-song cache sits behind a SoftReference the collector may clear.
    //
    // When the arrangement is already in memory -- the overwhelmingly common case, and always the
    // case for a restart or a speed change -- start runs INLINE on the calling thread. That keeps
    // the existing ordering intact: callers that read the player they just created still see it.
    // Only a genuinely cold song takes the slow path, and then start is handed to dispatcher so it
    // lands back on a thread allowed to touch the world.
    public static void whenReady(IPlayList list, Consumer<Runnable> dispatcher, Runnable start) {
        MusicBoxSong current = list != null ? (MusicBoxSong) list.getCurrent() : null;
        if (current == null || current.isCompiled() || dispatcher == null) {
            start.run();
            return;
        }
        AsyncTaskManager.runAsync(() -> {
            try {
                current.getCompiledSong();
            } catch (Exception ignored) {
                // A song that cannot be compiled still has to start: the player handles a null
                // arrangement by staying silent, and the block or disc behind it keeps working.
            }
            dispatcher.accept(start);
        });
    }

    // Builds the current song's arrangement on the CALLING thread. Only for callers that are
    // already off the server threads and about to hop back onto one -- doing the work there costs
    // nothing extra and saves the hop from stalling.
    public static void warm(IPlayList list) {
        MusicBoxSong current = list != null ? (MusicBoxSong) list.getCurrent() : null;
        if (current == null || current.isCompiled()) {
            return;
        }
        try {
            current.getCompiledSong();
        } catch (Exception ignored) {
            // The player handles a null arrangement by staying silent.
        }
    }

    // Builds the arrangement of whatever comes next in the playlist, off-thread, so the automatic
    // transition at the end of the current song does not pay for it. Silent about failures: this
    // is a hint, and the real load happens when that song actually starts.
    public static void prefetchNext(IPlayList list) {
        if (list == null || !list.hasNext()) {
            return;
        }
        java.util.List<? extends com.huidu.musicboxplus.api.song.MusicBoxSong> next = list.getNextSongs(1);
        if (next.isEmpty()) {
            return;
        }
        MusicBoxSong song = (MusicBoxSong) next.get(0);
        if (song == null || song.isCompiled()) {
            return;
        }
        AsyncTaskManager.runAsync(() -> {
            try {
                song.getCompiledSong();
            } catch (Exception ignored) {
                // Nothing depends on this having worked.
            }
        });
    }
}
