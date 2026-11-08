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
| `/musicbox` | 打开主音乐菜单 | `musicboxplus.use` |
| `/musicbox play <song> [speaker\|radio\|silent\|unsilent]` | 播放歌曲 | `musicboxplus.use` |
| `/musicbox stop [player]` | 停止自己或他人播放 | `musicboxplus.stop` / `musicboxplus.stop.others` |
| `/musicbox volume <0-100\|up\|down\|reset>` | 调节音量 | `musicboxplus.volume` |
| `/musicbox speed <value>` | 调节播放速度 | `musicboxplus.speed` |
| `/musicbox shop` | 打开商店 | `musicboxplus.shop` |
| `/musicbox shopmusic` | 玩家音乐商店别名 | `musicboxplus.shopmusic` |
| `/musicbox edit create <name>` | 创建音乐 | `musicboxplus.edit` |
| `/musicbox edit gui` | 打开编辑器 | `musicboxplus.edit` |
| `/musicbox reload [all\|config\|lang\|songs\|gui\|database\|aliases]` | 重载插件数据 | `musicboxplus.admin` |
| `/musicbox backup` | 把配置、语言文件、歌曲与 SQLite 数据库打包到 `backups/`（保留最新 10 份） | `musicboxplus.admin` |
| `/musicbox export <玩家\|uuid>` | 把该玩家全部作品导出为 `.nbs` 到 `exports/` | `musicboxplus.admin` |
| `/musicbox doctor` | 只读自检：数据库延迟、结构版本、曲库与缓存规模、模块开关 | `musicboxplus.admin` |

`backup` 会先对 SQLite 的 WAL 做 checkpoint，保证复制出的 `data.db` 含有最新写入；MySQL 后端不会把数据库放进压缩包，请用 `mysqldump`。`doctor` 不写任何数据、不触发重载，用来在不翻日志的情况下回答“它为什么是这个行为”。

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| `musicboxplus.use` | 基本使用 | true |
| `musicboxplus.stop` | 停止自己的播放 | true |
| `musicboxplus.stop.others` | 停止他人播放 | op |
| `musicboxplus.speaker` | 使用音箱模式 | true |
| `musicboxplus.hear` | 听到音箱音乐 | true |
| `musicboxplus.playlist` | 创建和管理播放列表 | true |
| `musicboxplus.volume` | 调节音量 | true |
| `musicboxplus.speed` | 调节播放速度 | true |
| `musicboxplus.autoplay` | 进服自动播放 | true |
| `musicboxplus.shop` | 使用商店 | true |
| `musicboxplus.shopmusic` | 玩家音乐商店 | true |
| `musicboxplus.edit` | 创建和编辑音乐 | op |
| `musicboxplus.edit.limit.unlimited` | 绕过每人作品数量上限 | op |
| `musicboxplus.sign` | 创建音乐告示牌 | op |
| `musicboxplus.sign.protect` | 保护音乐告示牌不被破坏 | op |
| `musicboxplus.give` | 给予唱片 | op |
| `musicboxplus.tag` | 管理歌曲标签与别名 | op |
| `musicboxplus.mute` | 停止他人当前音乐 | op |
| `musicboxplus.play.other` | 为他人播放 | op |
| `musicboxplus.silent.other` | 修改他人的静音模式 | op |
| `musicboxplus.control` | 远程控制他人播放 | op |
| `musicboxplus.publish.review` | 审核已发布作品 | op |
| `musicboxplus.reload` | 重载插件数据 | op |
| `musicboxplus.admin` | 管理命令（重载、备份、导出、自检） | op |

插件命令名是 `musicboxplus`，并以 `musicbox` 作为别名，两种写法都可用；权限节点统一使用 `musicboxplus.` 前缀，与 `plugin.yml` 一致。

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

每个歌曲文件夹可以放置 `folder.yml` 自定义文件夹物品，不需要在 GUI 配置中单独维护：

```yaml
display:
  item: CHEST
  custom-model-data: 0
  item-model: ""
  craft-engine-item: ""
  name: "<gold>{folder}</gold>"
  lore:
    - "<gray>歌曲数: {count}</gray>"
    - "{description}"
  description:
    - "这里是该文件夹的说明"
  glow: false
  skull-owner: ""
```

`display.description` 和 `display.lore` 都可以写成单行字符串；旧的 `custom_model_data`、`skull_owner` 写法仍兼容。没有 `folder.yml` 时继续读取同目录的 `info.txt` 作为 Lore，并在目录可写时自动生成等价的 `folder.yml`（不会删除 `info.txt`）。

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
