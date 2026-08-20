# MusicBoxPlus（中文）

在服务器上通过唱片、告示牌和 GUI 播放 [NBS 歌曲](https://opennbs.org/) 的 Paper 插件。**纯 Paper API 实现，不依赖 NoteBlockAPI 与 NMS**，无需逐版本维护。

> **支持平台：** Paper `1.21.4` ~ 最新（含 `26.x`），Java `21` 或更高，支持 Folia。
> 构建需要 JDK 25。

## 简介

MusicBoxPlus 是 [MusicBox](https://github.com/Spliterash/MusicBox) 的完全重写版本，参考了 [DaringShepard/MusicBox](https://github.com/DaringShepard/MusicBox) 的 Paper API 迁移思路。播放引擎、GUI、音乐编辑器、商店均为全新实现，不依赖 NoteBlockAPI 与 NMS。

## 功能

### 核心
- 唱片 / 告示牌 / GUI 播放 NBS 歌曲
- 纯 Paper API，无 NMS，无需 NoteBlockAPI
- 多语言支持（英文 / 中文）
- 可配置的完整 GUI 布局

### 音乐编辑器
- 游戏内钢琴式音乐编辑器
- 每个音符多乐器支持
- BPM、拍号设置
- 复制、粘贴、选择工具，支持撤销 / 重做
- 扩展音域 10 个八度

### 玩家音乐商店
- 发布音乐到商店，自定义价格
- 他人购买后获得收入，支持税收
- 搜索与浏览已发布音乐

### 播放特性
- 音箱 / 电台 / 静默等播放模式
- 音量、速度、循环控制
- 播放列表
- 进服自动播放

### 定制
- `gui-config.yml` 配置全部 GUI 布局
- 自定义模型数据 / 材质 / item model
- 可选 CraftEngine 物品支持

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/musicbox` | 打开主音乐菜单 | `musicbox.use` |
| `/musicbox play <song> [speaker\|radio\|silent\|unsilent]` | 播放歌曲 | `musicbox.use` |
| `/musicbox stop [player]` | 停止自己或他人播放 | `musicbox.stop` / `musicbox.stop.others` |
| `/musicbox volume <0-100\|up\|down\|reset>` | 调节音量 | `musicbox.volume` |
| `/musicbox speed <value>` | 调节播放速度 | `musicbox.speed` |
| `/musicbox shop` | 打开商店 | `musicbox.shop` |
| `/musicbox shopmusic` | 玩家音乐商店别名 | `musicbox.shopmusic` |
| `/musicbox edit create <name>` | 创建音乐 | `musicbox.edit` |
| `/musicbox edit gui` | 打开编辑器 | `musicbox.edit` |
| `/musicbox reload [all\|config\|lang\|songs\|gui\|database\|aliases]` | 重载插件数据 | `musicbox.admin` |

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| `musicbox.use` | 基本使用 | true |
| `musicbox.stop` | 停止自己的播放 | true |
| `musicbox.stop.others` | 停止他人播放 | op |
| `musicbox.speaker` | 使用音箱模式 | true |
| `musicbox.hear` | 听到音箱音乐 | true |
| `musicbox.playlist` | 创建和管理播放列表 | true |
| `musicbox.volume` | 调节音量 | true |
| `musicbox.speed` | 调节播放速度 | true |
| `musicbox.autoplay` | 进服自动播放 | true |
| `musicbox.loop` | 切换循环模式 | true |
| `musicbox.shop` | 使用商店 | true |
| `musicbox.shopmusic` | 玩家音乐商店 | true |
| `musicbox.jukebox` | 使用唱片机 | true |
| `musicbox.edit` | 创建和编辑音乐 | op |
| `musicbox.sign` | 创建音乐告示牌 | op |
| `musicbox.give` | 给予唱片 | op |
| `musicbox.mute` | 停止他人当前音乐 | op |
| `musicbox.play.other` | 为他人播放 | op |
| `musicbox.admin` | 管理命令（重载等） | op |

## 配置

```
plugins/MusicBoxPlus/
├── config.yml           # 主配置（经济、商店、播放、数据库、缓存）
├── gui-config.yml       # GUI 布局
├── language_en.yml      # 英文语言文件
├── language_zh_cn.yml   # 中文语言文件
├── songs/               # 曲库目录
└── data.db              # SQLite 数据库（默认）
```

支持 MySQL（见 `db/MySQL.sql`）。可选集成：Vault（经济）、PlaceholderAPI（占位符）、CraftEngine（物品模型）。

## 构建

```bash
./gradlew build
```

编译产物位于 `build/libs/`，为包含 HikariCP 与 `musicbox-api` 类的 shade 包。
供下游插件依赖的 api 构件为 `musicbox-api/build/libs/musicbox-api-<version>.jar`。

需要 JDK 25（paper-api 26.x 的 class 文件版本为 69）；字节码目标为 Java 21。

## 参考与致谢

- **原始插件：** [Spliterash/MusicBox](https://github.com/Spliterash/MusicBox)
- **Paper API 迁移参考：** [DaringShepard/MusicBox](https://github.com/DaringShepard/MusicBox)
- **NBS 歌曲格式：** [OpenNBS](https://opennbs.org/)

## English

English documentation is available at [README.md](README.md).
