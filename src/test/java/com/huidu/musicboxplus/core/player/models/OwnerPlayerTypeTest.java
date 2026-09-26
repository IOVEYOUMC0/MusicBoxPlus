package com.huidu.musicboxplus.core.player.models;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// The model's back-reference to its owning player must stay typed as MusicBoxSongPlayer.
//
// Held as NoteBlockAPI's SongPlayer instead, the only way to reach isPersistentOnEnd() is an
// instanceof. That compiles either way and is simply false for any player outside the
// NoteBlockAPI hierarchy, at which point a text player tears itself down -- along with its
// floating display, permanently -- the moment its playlist ends, while also reporting a
// spurious SONG_END and running the jukebox eject / sign pulse side effects.
//
// Nothing about that failure is visible at compile time, and no behavioural test can reach it
// without a running server, so the type itself is what gets asserted here.
class OwnerPlayerTypeTest {

    @Test
    void ownerPlayerIsTypedSoPersistenceIsReachableWithoutInstanceof() throws Exception {
        Field field = MusicBoxSongPlayerModel.class.getDeclaredField("ownerPlayer");
        assertEquals(MusicBoxSongPlayer.class, field.getType(),
                "ownerPlayer must be a MusicBoxSongPlayer; widening it back to a third-party "
                        + "player type makes isPersistentOnEnd() unreachable except by instanceof");
        assertTrue(Modifier.isVolatile(field.getModifiers()),
                "ownerPlayer is written on one thread and read from the song-end callback on "
                        + "another, so it must be volatile");
    }

    // The dead half of the old dual-purpose field: the model used to be able to build a
    // playback engine of its own, reachable only through entry points that nothing called.
    // Those paths made every read ambiguous about which of the two roles it was looking at.
    @Test
    void theModelNoLongerOwnsAPlaybackEngine() {
        for (String gone : new String[]{"runPlayer", "startNext", "getSongPlayer", "setSongPlayer"}) {
            for (Method method : MusicBoxSongPlayerModel.class.getDeclaredMethods()) {
                assertFalse(method.getName().equals(gone), method.getName() + " was removed with the model-owned engine path; "
                        + "reintroducing it brings back the ambiguity this type split resolved");
            }
        }
    }

    @Test
    void persistenceIsAskedOfTheOwnerDirectly() throws Exception {
        Method method = MusicBoxSongPlayer.class.getMethod("isPersistentOnEnd");
        assertEquals(boolean.class, method.getReturnType());
        assertTrue(method.isDefault(), "isPersistentOnEnd must stay a default method so that "
                + "players opting out of self-destruction only override what they need");
    }
}
