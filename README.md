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
| `/musicbox` | Open main music menu | `musicboxplus.use` |
| `/musicbox play <song> [speaker\|radio\|silent\|unsilent]` | Play a song | `musicboxplus.use` |
| `/musicbox stop [player]` | Stop your own or another player's playback | `musicboxplus.stop` / `musicboxplus.stop.others` |
| `/musicbox volume <0-100\|up\|down\|reset>` | Adjust volume | `musicboxplus.volume` |
| `/musicbox speed <value>` | Adjust playback speed | `musicboxplus.speed` |
| `/musicbox shop` | Open the shop | `musicboxplus.shop` |
| `/musicbox shopmusic` | Player music shop alias | `musicboxplus.shopmusic` |
| `/musicbox edit create <name>` | Create music | `musicboxplus.edit` |
| `/musicbox edit gui` | Open the editor | `musicboxplus.edit` |
| `/musicbox reload [all\|config\|lang\|songs\|gui\|database\|aliases]` | Reload plugin data | `musicboxplus.admin` |
| `/musicbox backup` | Zip config, language files, songs and the SQLite database into `backups/` (keeps the newest 10) | `musicboxplus.admin` |
| `/musicbox export <player\|uuid>` | Write every song that player owns as `.nbs` into `exports/` | `musicboxplus.admin` |
| `/musicbox doctor` | Read-only snapshot: database latency, schema version, library and cache sizes, module flags | `musicboxplus.admin` |

`backup` checkpoints SQLite's write-ahead log first, so the copied `data.db` really contains the
newest writes. With MySQL the archive deliberately contains no database file — dump it with
`mysqldump`. `doctor` never writes or reloads anything; it is meant to answer "why is it behaving
like that" without reading logs.

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `musicboxplus.use` | Basic usage | true |
| `musicboxplus.stop` | Stop your own playback | true |
| `musicboxplus.stop.others` | Stop another player's playback | op |
| `musicboxplus.speaker` | Use speaker mode | true |
| `musicboxplus.hear` | Hear music from speakers | true |
| `musicboxplus.playlist` | Create and manage playlists | true |
| `musicboxplus.volume` | Adjust volume | true |
| `musicboxplus.speed` | Adjust playback speed | true |
| `musicboxplus.autoplay` | Auto-play on join | true |
| `musicboxplus.shop` | Use the shop | true |
| `musicboxplus.shopmusic` | Player music shop | true |
| `musicboxplus.edit` | Create and edit music | op |
| `musicboxplus.edit.limit.unlimited` | Bypass the per-player song limit | op |
| `musicboxplus.sign` | Create music signs | op |
| `musicboxplus.sign.protect` | Protect music signs from destruction | op |
| `musicboxplus.give` | Give music discs | op |
| `musicboxplus.tag` | Manage song tags and aliases | op |
| `musicboxplus.mute` | Stop another player's current music | op |
| `musicboxplus.play.other` | Play songs for other players | op |
| `musicboxplus.silent.other` | Change silent mode for another player | op |
| `musicboxplus.control` | Remote-control another player's playback | op |
| `musicboxplus.publish.review` | Review published music | op |
| `musicboxplus.reload` | Reload plugin data | op |
| `musicboxplus.admin` | Admin commands (reload, backup, export, doctor) | op |

The plugin's command is `musicboxplus`, with `musicbox` as an alias, so both spellings work. Permission nodes keep the `musicboxplus.` prefix, matching `plugin.yml`.

## Configuration

```
plugins/MusicBoxPlus/
├── config.yml           # Main configuration (economy, shop, playback, database, cache)
├── gui-config.yml       # GUI layouts
├── language_en.yml      # English language file
├── language_zh_cn.yml   # Chinese language file
├── songs/               # Song library directory
├── data.db              # SQLite database (default)
└── jukebox-resume.yml   # Jukeboxes the plugin last saw stopped, so a reload does not restart them
```

Each song folder can define its own folder item in `folder.yml`; no separate GUI entry is needed:

```yaml
display:
  item: CHEST
  custom-model-data: 0
  item-model: ""
  craft-engine-item: ""
  name: "<gold>{folder}</gold>"
  lore:
    - "<gray>Songs: {count}</gray>"
    - "{description}"
  description:
    - "Description for this folder"
  glow: false
  skull-owner: ""
```

`display.description` and `display.lore` may also be a single string. Legacy `custom_model_data` and `skull_owner` keys remain supported. Without `folder.yml`, the folder's `info.txt` is still used as lore and is automatically migrated to an equivalent `folder.yml` when the directory is writable (`info.txt` is kept).

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
