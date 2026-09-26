-- Creator:       MySQL Workbench 8.0.21/ExportSQLite Plugin 0.1.0
-- Author:        Desktop
-- Caption:       New Model
-- Project:       Name of the project
-- Changed:       2020-09-08 20:59
-- Created:       2020-08-16 19:12
CREATE TABLE IF NOT EXISTS "playlists"
(
    "id"    INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    "owner" VARCHAR(64)                       NOT NULL,
    "name"  VARCHAR(256)                      NOT NULL
);
CREATE TABLE IF NOT EXISTS "signs"
(
    "location" VARCHAR(256) PRIMARY KEY NOT NULL
);
CREATE TABLE IF NOT EXISTS "playlist_song"
(
    "playlists_id" INTEGER NOT NULL,
    "song_hash"    INTEGER NOT NULL,
    "pos"          INTEGER NOT NULL,
    PRIMARY KEY ("playlists_id", "song_hash"),
    CONSTRAINT "fk_playlist_song_playlists1"
        FOREIGN KEY ("playlists_id")
            REFERENCES "playlists" ("id")
            ON DELETE CASCADE
            ON UPDATE CASCADE
);
CREATE TABLE IF NOT EXISTS "sign_songs"
(
    "location" VARCHAR(256) PRIMARY KEY NOT NULL,
    "song_hash" INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS "recent_songs"
(
    "player_uuid" VARCHAR(36) NOT NULL,
    "song_hash"   INTEGER NOT NULL,
    "pos"         INTEGER NOT NULL,
    PRIMARY KEY ("player_uuid", "song_hash")
);
CREATE TABLE IF NOT EXISTS "player_volumes"
(
    "player_uuid" VARCHAR(36) PRIMARY KEY NOT NULL,
    "volume"      INTEGER NOT NULL DEFAULT 100
);
CREATE TABLE IF NOT EXISTS "block_player_volumes"
(
    "location" VARCHAR(256) PRIMARY KEY NOT NULL,
    "volume"   INTEGER NOT NULL DEFAULT 100
);
CREATE TABLE IF NOT EXISTS "player_music"
(
    "id"             VARCHAR(36) PRIMARY KEY NOT NULL,
    "name"           VARCHAR(256) NOT NULL,
    "author"         VARCHAR(64) NOT NULL,
    "author_uuid"    VARCHAR(36) NOT NULL,
    "time_signature" VARCHAR(8) NOT NULL DEFAULT '4/4',
    "bpm"            INTEGER NOT NULL DEFAULT 120,
    "beat_subdivision" INTEGER NOT NULL DEFAULT 4,
    "created_at"     INTEGER NOT NULL,
    "updated_at"     INTEGER NOT NULL,
    "description"    TEXT
);
CREATE TABLE IF NOT EXISTS "player_music_notes"
(
    "id"         INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    "music_id"   VARCHAR(36) NOT NULL,
    "pitch"      INTEGER NOT NULL,
    "tick"       INTEGER NOT NULL,
    "instruments" TEXT NOT NULL,
    CONSTRAINT "fk_player_music_notes_music"
        FOREIGN KEY ("music_id")
            REFERENCES "player_music" ("id")
            ON DELETE CASCADE
            ON UPDATE CASCADE
);
-- playlists.owner is the only non-key column any query filters on (getPlayLists:
-- "where p.owner = ?"); MySQL.sql already had this index and SQLite did not.
CREATE INDEX IF NOT EXISTS "idx_playlists_owner" ON "playlists" ("owner");
CREATE INDEX IF NOT EXISTS "idx_player_music_notes_music_id" ON "player_music_notes" ("music_id");
