-- MySQL 建表脚本
-- MusicBox 插件数据库结构

CREATE TABLE IF NOT EXISTS playlists
(
    id    INT PRIMARY KEY AUTO_INCREMENT NOT NULL,
    owner VARCHAR(64)                     NOT NULL,
    name  VARCHAR(256)                    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS signs
(
    location VARCHAR(256) PRIMARY KEY NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS playlist_song
(
    playlists_id INT NOT NULL,
    song_hash    INT NOT NULL,
    pos          INT NOT NULL,
    PRIMARY KEY (playlists_id, song_hash),
    CONSTRAINT fk_playlist_song_playlists
        FOREIGN KEY (playlists_id)
            REFERENCES playlists (id)
            ON DELETE CASCADE
            ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sign_songs
(
    location  VARCHAR(256) PRIMARY KEY NOT NULL,
    song_hash INT                      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS recent_songs
(
    player_uuid VARCHAR(36) NOT NULL,
    song_hash   INT         NOT NULL,
    pos         INT         NOT NULL,
    PRIMARY KEY (player_uuid, song_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_volumes
(
    player_uuid VARCHAR(36) PRIMARY KEY NOT NULL,
    volume      INT                     NOT NULL DEFAULT 100
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS block_player_volumes
(
    location VARCHAR(256) PRIMARY KEY NOT NULL,
    volume   INT                      NOT NULL DEFAULT 100
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_music
(
    id             VARCHAR(36) PRIMARY KEY NOT NULL,
    name           VARCHAR(256) NOT NULL,
    author         VARCHAR(64) NOT NULL,
    author_uuid    VARCHAR(36) NOT NULL,
    time_signature VARCHAR(8) NOT NULL DEFAULT '4/4',
    bpm            INT NOT NULL DEFAULT 120,
    beat_subdivision INT NOT NULL DEFAULT 4,
    created_at     BIGINT NOT NULL,
    updated_at     BIGINT NOT NULL,
    description    TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_music_notes
(
    id         INT PRIMARY KEY AUTO_INCREMENT NOT NULL,
    music_id   VARCHAR(36) NOT NULL,
    pitch      INT NOT NULL,
    tick       INT NOT NULL,
    instruments TEXT NOT NULL,
    CONSTRAINT fk_player_music_notes_music
        FOREIGN KEY (music_id)
            REFERENCES player_music (id)
            ON DELETE CASCADE
            ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建索引以提高查询性能
CREATE INDEX idx_playlists_owner ON playlists(owner);
CREATE INDEX idx_player_music_notes_music_id ON player_music_notes(music_id);
