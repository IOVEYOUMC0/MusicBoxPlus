package com.huidu.musicboxplus.core.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The rule that stops a playlist edit from deleting songs whose files are temporarily missing.
//
// savePlayList rewrites a playlist's rows from scratch (DELETE, then INSERT one row per hash), so the
// list this decides is not "what to add" -- it is "what survives". Reading a playlist deliberately
// keeps unresolvable hashes in the database, and the write path used to rebuild the rows from the
// resolved songs alone, which dropped every one of them for good on the next unrelated edit.
class PlaylistRowHashTest {

    @Test
    void keepsSongsThatCouldNotBeResolved() {
        // 11 and 22 are loaded songs; 33 belongs to a .nbs that is currently renamed or missing.
        List<Integer> written = AbstractBase.playlistRowHashes(List.of(11, 22), List.of(33));

        assertEquals(List.of(11, 22, 33), written,
                "a song that could not be resolved was dropped from the rewrite");
    }

    @Test
    void keepsTheOrderOfTheSongsTheUserCanSee() {
        assertEquals(List.of(7, 3, 9, 4), AbstractBase.playlistRowHashes(List.of(7, 3, 9), List.of(4)),
                "the visible order is the user's order and must not change");
    }

    @Test
    void aSongTheUserRemovedIsNotWrittenBack() {
        // The removed hash is in neither list. This is what separates the merge from "keep every row
        // that was ever there", which would make removeSong a no-op.
        assertEquals(List.of(11, 33), AbstractBase.playlistRowHashes(List.of(11), List.of(33)));
    }

    @Test
    void handlesBothListsBeingEmptyAndOnlyUnresolved() {
        assertEquals(List.of(), AbstractBase.playlistRowHashes(List.of(), List.of()));
        // Every song missing: the rows still have to be written back, or the next save of a playlist
        // that is invisible in the GUI would erase it.
        assertEquals(List.of(5, 6), AbstractBase.playlistRowHashes(List.of(), List.of(5, 6)));
    }
}
