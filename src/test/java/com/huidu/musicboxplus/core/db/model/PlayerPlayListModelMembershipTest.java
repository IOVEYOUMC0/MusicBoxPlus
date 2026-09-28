package com.huidu.musicboxplus.core.db.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.song.MusicBoxSong;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// hasSong answers from a HashSet built from the playlist's songs, replacing a LinkedList scan that
// the add-song picker paid once per rendered song item.
//
// The two lookups only agree while MusicBoxSong keeps identity equality: a set needs hashCode to be
// consistent with equals, and a value-based equals without a matching hashCode would make the set
// miss songs the list still finds. That invariant cannot be tested through the model itself here --
// constructing a MusicBoxSong needs a live plugin, because its constructor asks MusicBox for the
// songs folder -- so it is pinned reflectively instead.
class PlayerPlayListModelMembershipTest {

    @Test
    void hasSongRejectsNull() {
        PlayerPlayListModel model = new PlayerPlayListModel(1, UUID.randomUUID(), "list");

        assertFalse(model.hasSong(null), "a null song is never a member");
    }

    @Test
    void anEmptyPlaylistHoldsNothing() {
        PlayerPlayListModel model = new PlayerPlayListModel(1, UUID.randomUUID(), "list");

        assertFalse(model.hasSong(null));
        assertTrue(model.getSongs().isEmpty());
    }

    @Test
    void songsStillUseIdentityEquality() {
        assertDeclaredOnObject("hashCode");
        assertDeclaredOnObject("equals", Object.class);
    }

    private static void assertDeclaredOnObject(String name, Class<?>... parameters) {
        Method method;
        try {
            method = MusicBoxSong.class.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("MusicBoxSong no longer has " + name, e);
        }
        assertEquals(Object.class, method.getDeclaringClass(),
                "MusicBoxSong overrides " + name + ": re-check PlayerPlayListModel.hasSong, whose set "
                        + "lookup is only equivalent to the list's contains while equality is identity");
    }
}
