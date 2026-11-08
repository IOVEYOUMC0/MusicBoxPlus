package com.huidu.musicboxplus.core.db;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The upsert is what turns a save into an in-place update. On SQLite the old form was
// "INSERT OR REPLACE", which deletes the conflicting row and re-inserts it -- and that fired
// ON DELETE CASCADE from player_music into player_music_notes, so a metadata-only save (the note
// table is skipped when its fingerprint is unchanged) wiped every note of the song. These tests
// pin the generated SQL, which is the only part of that failure reachable without a live driver.
class UpsertSqlTest {

    private static AbstractBase backend(boolean mysql) {
        return new AbstractBase(mysql ? "MySQL" : "SQLite") {
            @Override
            public boolean isMySQL() {
                return mysql;
            }

            @Override
            protected java.sql.Connection acquireConnection() {
                // SQL generation is pure; no test here opens a connection.
                throw new UnsupportedOperationException("no database in this test");
            }
        };
    }

    private static String sqlite(String table, String[] columns, String[] keys) {
        return backend(false).getUpsertSql(table, columns, keys);
    }

    private static String mysql(String table, String[] columns, String[] keys) {
        return backend(true).getUpsertSql(table, columns, keys);
    }

    @Test
    void sqliteUpdatesInPlaceInsteadOfReplacingTheRow() {
        String sql = sqlite("player_music",
                new String[]{"id", "name", "bpm"},
                new String[]{"id"});

        assertFalse(sql.toUpperCase(Locale.ROOT).contains("INSERT OR REPLACE"),
                "REPLACE deletes the row, which cascades into player_music_notes: " + sql);
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"), sql);
        assertTrue(sql.contains("name = excluded.name"), sql);
        assertTrue(sql.contains("bpm = excluded.bpm"), sql);
        assertFalse(sql.contains("id = excluded.id"),
                "the key column must not be part of the update list: " + sql);
    }

    @Test
    void sqliteCompositeKeyConflictTargetListsEveryKeyColumn() {
        String sql = sqlite("recent_songs",
                new String[]{"player_uuid", "song_hash", "pos"},
                new String[]{"player_uuid", "song_hash"});

        assertTrue(sql.contains("ON CONFLICT (player_uuid, song_hash) DO UPDATE SET"), sql);
        assertTrue(sql.contains("pos = excluded.pos"), sql);
    }

    @Test
    void sqliteUpsertWithOnlyKeyColumnsDoesNothingOnConflict() {
        String sql = sqlite("signs", new String[]{"location"}, new String[]{"location"});

        assertTrue(sql.endsWith("DO NOTHING"), "an empty DO UPDATE SET would not parse: " + sql);
    }

    @Test
    void mysqlStillUpdatesNonKeyColumns() {
        String sql = mysql("player_music",
                new String[]{"id", "name", "bpm"},
                new String[]{"id"});

        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), sql);
        assertTrue(sql.contains("name = VALUES(name)"), sql);
        assertTrue(sql.contains("bpm = VALUES(bpm)"), sql);
        assertFalse(sql.contains("id = VALUES(id)"),
                "the key column must not be part of the update list: " + sql);
    }

    @Test
    void mysqlUpsertWithOnlyKeyColumnsIsStillValidSql() {
        String sql = mysql("signs", new String[]{"location"}, new String[]{"location"});

        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE location = location"),
                "an empty update list would not parse: " + sql);
    }
}
