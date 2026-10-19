package com.huidu.musicboxplus.module.jukebox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// The rules that keep a finished disc from starting over, and the file that carries them across a
// restart. Wrong rules here are silent: a jukebox that should have stayed silent starts playing
// again on the next reload, or one that had a different disc in it never resumes.
class JukeboxPlaybackStateTest {

    private static final String WORLD = "world";
    private static final String KEY = JukeboxPlaybackState.key(WORLD, 10, 64, -3);

    @Test
    void anUnknownJukeboxStillResumes() {
        // Nothing recorded means "the plugin never watched this block stop", which must keep the old
        // behaviour of starting on its own -- otherwise a fresh install would silently change how
        // every existing jukebox behaves.
        assertTrue(new JukeboxPlaybackState.Index().shouldResume(KEY, 123));
    }

    @Test
    void aFinishedSongIsNotResumed() {
        JukeboxPlaybackState.Index index = new JukeboxPlaybackState.Index();
        index.markNotPlaying(KEY, 123);

        assertFalse(index.shouldResume(KEY, 123), "the disc that finished must stay silent");
        assertTrue(index.shouldResume(JukeboxPlaybackState.key(WORLD, 11, 64, -3), 123),
                "another jukebox in the same world is unaffected");
        assertTrue(index.shouldResume(JukeboxPlaybackState.key("other", 10, 64, -3), 123),
                "the same coordinates in another world are unrelated");
    }

    @Test
    void playingAgainClearsTheMark() {
        JukeboxPlaybackState.Index index = new JukeboxPlaybackState.Index();
        index.markNotPlaying(KEY, 123);
        assertTrue(index.markPlaying(KEY));
        assertTrue(index.shouldResume(KEY, 123));
        assertEquals(0, index.size());
        assertFalse(index.markPlaying(KEY), "clearing an absent mark is not a state change");
    }

    @Test
    void aDifferentDiscDoesNotInheritTheFinishedState() {
        JukeboxPlaybackState.Index index = new JukeboxPlaybackState.Index();
        index.markNotPlaying(KEY, 123);

        // The player swapped the disc without the plugin ever starting a player here: the mark is
        // stale and must not silence the new song.
        assertTrue(index.shouldResume(KEY, 999), "a different song resumes");
        assertEquals(0, index.size(), "and the stale mark is dropped rather than kept");
        assertTrue(index.shouldResume(KEY, 123), "so the old song is no longer suppressed either");
    }

    @Test
    void marksRoundTripThroughTheFile() throws Exception {
        JukeboxPlaybackState.Index index = new JukeboxPlaybackState.Index();
        index.markNotPlaying(JukeboxPlaybackState.key(WORLD, 10, 64, -3), 123);
        index.markNotPlaying(JukeboxPlaybackState.key("mine world", -12, -64, 35), 456);

        List<Map<String, Object>> records = JukeboxPlaybackState.toRecords(index);
        List<Map<String, Object>> reread = JukeboxPlaybackState.fromYaml(JukeboxPlaybackState.toYaml(records));

        assertEquals(2, reread.size());
        Map<String, Object> first = reread.get(0);
        assertEquals(WORLD, first.get("world"));
        assertEquals(10, ((Number) first.get("x")).intValue());
        assertEquals(64, ((Number) first.get("y")).intValue());
        assertEquals(-3, ((Number) first.get("z")).intValue());
        assertEquals(123, ((Number) first.get("song")).intValue());
        // A world name with a space is the case a plain "world;x;y;z" key would have split wrong.
        Map<String, Object> second = reread.get(1);
        assertEquals("mine world", second.get("world"));
        assertEquals(-64, ((Number) second.get("y")).intValue());
        assertEquals(456, ((Number) second.get("song")).intValue());
    }

    @Test
    void aMissingRootReadsAsNoMarks() throws Exception {
        assertEquals(List.of(), JukeboxPlaybackState.fromYaml(""));
        assertEquals(List.of(), JukeboxPlaybackState.fromYaml("jukeboxes: []\n"));
    }
}
