# MusicBoxPlus

Play [NBS songs](https://opennbs.org/) on your Paper server through records, signs, and a GUI menu. Pure Paper API implementation — **no NoteBlockAPI, no NMS** — so it keeps working across versions without per-version maintenance.

> **Supported platforms:** Paper `1.21.4` ~ latest (including `26.x`), Java `21` or newer, Folia supported.
> Building requires JDK 25.

## Introduction

MusicBoxPlus is a full rewrite of [MusicBox](https://github.com/Spliterash/MusicBox), inspired by the Paper API migration approach of [DaringShepard/MusicBox](https://github.com/DaringShepard/MusicBox). The playback engine, GUI, music editor, and shop are all newly implemented with no dependency on NoteBlockAPI or NMS.

## Features

### Core
- Play NBS songs via records, signs, and GUI
- Pure Paper API, no NMS, no NoteBlockAPI
- Multi-language support (English / 中文)
- Fully configurable GUI layouts

### Music Editor
- In-game piano-style music editor
- Multiple instruments per note
- BPM and time signature settings
- Copy, paste, and selection tools with undo/redo
- Extended range of 10 octaves

### Player Music Shop
- Publish your music to the shop with custom prices
- Earn money when others buy your music, tax supported
- Search and browse published music

### Playback
- Speaker / radio / silent playback modes
- Volume, speed, and loop controls
- Playlists
- Auto-play on join

### Customization
- All GUI layouts configurable via `gui-config.yml`
- Custom model data / materials / item models
- Optional CraftEngine item support

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/musicbox` | Open main music menu | `musicbox.use` |
| `/musicbox play <song> [speaker\|radio\|silent\|unsilent]` | Play a song | `musicbox.use` |
| `/musicbox stop [player]` | Stop your own or another player's playback | `musicbox.stop` / `musicbox.stop.others` |
| `/musicbox volume <0-100\|up\|down\|reset>` | Adjust volume | `musicbox.volume` |
| `/musicbox speed <value>` | Adjust playback speed | `musicbox.speed` |
| `/musicbox shop` | Open the shop | `musicbox.shop` |
| `/musicbox shopmusic` | Player music shop alias | `musicbox.shopmusic` |
| `/musicbox edit create <name>` | Create music | `musicbox.edit` |
| `/musicbox edit gui` | Open the editor | `musicbox.edit` |
| `/musicbox reload [all\|config\|lang\|songs\|gui\|database\|aliases]` | Reload plugin data | `musicbox.admin` |

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `musicbox.use` | Basic usage | true |
| `musicbox.stop` | Stop your own playback | true |
| `musicbox.stop.others` | Stop another player's playback | op |
| `musicbox.speaker` | Use speaker mode | true |
| `musicbox.hear` | Hear music from speakers | true |
| `musicbox.playlist` | Create and manage playlists | true |
| `musicbox.volume` | Adjust volume | true |
| `musicbox.speed` | Adjust playback speed | true |
| `musicbox.autoplay` | Auto-play on join | true |
| `musicbox.loop` | Change loop mode | true |
| `musicbox.shop` | Use the shop | true |
| `musicbox.shopmusic` | Player music shop | true |
| `musicbox.jukebox` | Use jukeboxes | true |
| `musicbox.edit` | Create and edit music | op |
| `musicbox.sign` | Create music signs | op |
| `musicbox.give` | Give music discs | op |
| `musicbox.mute` | Stop another player's current music | op |
| `musicbox.play.other` | Play songs for other players | op |
| `musicbox.admin` | Admin commands (reload, etc.) | op |

## Configuration

```
plugins/MusicBoxPlus/
├── config.yml           # Main configuration (economy, shop, playback, database, cache)
├── gui-config.yml       # GUI layouts
├── language_en.yml      # English language file
├── language_zh_cn.yml   # Chinese language file
├── songs/               # Song library directory
└── data.db              # SQLite database (default)
```

MySQL is supported (see `db/MySQL.sql`). Optional integrations: Vault (economy), PlaceholderAPI (placeholders), CraftEngine (item models).

## Building

```bash
./gradlew build
```

The shaded JAR (includes HikariCP and the `musicbox-api` classes) will be in `build/libs/`.
The api artifact for downstream plugins is `musicbox-api/build/libs/musicbox-api-<version>.jar`.

Requires JDK 25 (paper-api 26.x ships class file version 69); bytecode targets Java 21.

## Credits

- **Original plugin:** [Spliterash/MusicBox](https://github.com/Spliterash/MusicBox)
- **Paper API migration reference:** [DaringShepard/MusicBox](https://github.com/DaringShepard/MusicBox)
- **NBS song format:** [OpenNBS](https://opennbs.org/)

## 中文

中文版文档见 [README_zh_CN.md](README_zh_CN.md)。
