# Architecture Notes

The tree is organised into five top-level areas under `com.huidu.musicboxplus`. This note records what
each one is for and — more importantly — **which direction dependencies are allowed to point**.

## Layers

| Layer | Contains | May depend on |
|---|---|---|
| `api` | Public events and player interfaces third-party plugins compile against | *(nothing internal — see violations below)* |
| `common` | Language, scheduler, caches, NBT adapters, pure helpers (no business state) | `api` |
| `core` | Playback engine: players, models, playlists, song index, DB access, lifecycle | `api`, `common` |
| `module` | Features: commands, GUIs, jukebox, sign, speaker, radio, text display, editor, web, hooks | `api`, `common`, `core` |
| `shadow` | Relocated third-party bytecode (HikariCP). Not ours — do not do feature work here. | — |

`common.stats` is a vendored bStats implementation and follows the same "don't touch" rule as `shadow`.

### Rule

**Dependencies point downward only.** `api` must not import `core` or `module`; `common` must not
import `core` or `module`; `core` must not import `module`.

### Enforcement

The rules above are **enforced by ArchUnit** in
`src/test/java/com/huidu/musicboxplus/architecture/LayerDependencyRuleTest.java` — three tests:
`api` depends on nothing internal, `common` depends on nothing but `api`, `core` depends on
nothing but `api`/`common`. A refactor that reintroduces an upward dependency fails `gradlew build`.
Do not weaken the rules; fix the code instead.

The full history of `core → module` edges is gone:
- `MusicBoxSong`/`MusicBoxSongManager` → editor compiler / resource-pack instruments / MIDI
  converter — replaced by the `PlayerSongServices` registry (`core.song`), whose
  implementations are registered by `module` at startup (`ModuleRuntimeSync.syncPlayerSongServices`).
- `MusicBoxSongPlayerModel` → `SongPlayerControlGUI` — replaced by a `ControlGuiFactory`
  registration, same pattern.
- `MusicBoxSongContainer.createGUI` → `SongContainerGUI` — deleted; callers construct the GUI
  directly.
- `PlaybackContext` → `JukeboxPlayer` — replaced by the marker interface
  `api.player.VanillaJukeboxPlayback`, so the check is an `instanceof` against an `api` type.
- `PlayerWrapper` → `RadioPlayer`/`SpeakerPlayer` — replaced by `PlayerFactory` registrations
  (`PlayerWrapper.setPlayerFactories`), and its GUI fields now type the marker interface
  `core.gui.PlayerGUI` implemented by `module.gui.minecraft.GUI`.
- `core.lifecycle.ReloadPlaybackState` — moved to `module.lifecycle`; it orchestrates module
  players, so it belongs in the layer that owns them.
- `core.lifecycle.ModuleRuntimeSync` coordinating module listeners from core is gone: it now
  lives under `module` and owns the cross-module listeners too.

The former `common → core/module` edges are gone too. The DB layer (`AbstractBase`,
`DatabaseLoader`, `RuntimeDatabaseUtils`, models, backend types, statement/result helpers)
moved from `common.db` to `core.db` — it stores playback data, so it belongs with the playback
engine. `SongUtils` moved to `core.playback`; `SongAliasConfig` moved to `core.song`; and
`SignUtils` was split, with the playlist-parsing half living in `module.sign.SignPlaylistUtils`.

The former `api → core` model dependencies are **gone**: `api` now carries its own read-only
`MusicBoxSong` contract, `LoopMode`, the `MusicBoxSongPlayerModel` contract and the
`PlayerControlGUI` interface, and the events type only `api` types. `MusicBoxAPI` delegates to a
`MusicBoxApiService` implementation registered at startup. `api` lives in its own Gradle module
(`musicbox-api`) that depends on nothing internal, so downstream plugins can compile against it
directly.

## Web editor module

`module.web` is split by role rather than one god class:

- `WebEditorServer` — lifecycle only: config snapshot, HTTP server, session creation, cleanup.
- `WebApiSupport` — shared request plumbing for every `/api` route: CORS, rate limiting,
  security headers, session extraction, note/pitch/tick validation, music payload building.
- One handler class per route: `MusicApiHandler` (GET/PUT music), `ImportApiHandler`
  (server-path + multipart file import), `InstrumentApiHandler`, `SessionApiHandler`,
  `SettingsApiHandler`. `WebAssetHandler` serves the bundled front-end (and any overrides under
  `plugins/MusicBox/web/`) with gzip, ETag/304 and a bounded cache.
- The front-end (`resources/web`) is a single-file piano-roll editor. Cell events are delegated
  to the note grid container instead of one listener per cell, so large songs (64x120 grid) do
  not attach tens of thousands of handlers. Grid scroll reuses the rendered cells (only the
  data-tick each cell points at is re-decorated), and zoom (Ctrl+wheel) is merged to one render
  per animation frame. Playback speed and a metronome pulse are client-side only.

## Public API surface

`api` is the contract other plugins compile against. Changing it breaks them silently at runtime.

- `api.event.MusicBoxPlayerDestroyEvent` — fired unconditionally at the top of
  `AbstractBlockPlayer.destroy()`, carrying a `DestroyReason`. This is the **only** notification that
  covers every termination path (song end, manual stop, block gone, record removed, chunk/world
  unload, reload, shutdown). Anything that tears a player down must route through `destroy(reason)`.
- `api.event.MusicBoxPause/Resume/Stop/SongChangeEvent` — all carry a `getLocation()` snapshot so
  listeners never need `instanceof PositionPlayer`.
- `MusicBoxSongPlayer.pause()/resume()` return `true` when the change actually happened and
  `false` when a listener cancelled it. **`stop()` is the other way round**: it returns `true`
  when a listener *vetoed* the stop, so an explicit stop reads
  `if (!player.stop()) player.destroy(DestroyReason.MANUAL_STOP);`.

## Build

- Bytecode target is Java 21, but **the build requires JDK 25**: `paper-api` 26.x ships class file
  version 69, which `javac` 21 cannot read. Same toolchain split CraftEngine uses.
- Multi-module Gradle build (wrapper, 9.6): the root project is the plugin, `musicbox-api` is a
  library subproject the plugin depends on and shades in, so the published plugin jar is
  unchanged while the api artifact (`musicbox-api-<version>.jar`, plus sources) can be published
  separately for downstream plugins.
- `paper.api.version` and `adventure.version` live at the top of `build.gradle.kts` and are pinned
  together — adventure must match the `adventure-bom` that `paper-api` imports, or Paper's own
  Component API stops resolving.
- The platform line is dictated by CraftEngine, because CustomJukeBox hard-depends on it.

## Unwired code

These exist and compile but nothing references them. They are unfinished wiring, **not** dead code —
decide whether to wire them up or drop them, don't let them rot silently:

- `core.playback.AutoPlayService` — autoplay-on-join, honours config/permission/opt-out
- `module.edit.io.MidiAutoConverter` — walks song folders converting `.mid` → `.nbs`
- `module.edit.gui.EditGUIListener` — a `Listener` that is never registered

## Cleanup priorities

Status of the previously tracked items:

1. ~~Move the two jukebox listeners out of `api`~~ — done (`module.jukebox.listener`).
2. ~~`MusicBoxSongPlayer.getControl()`~~ — `SongPlayerControlGUI` is behind
   `api.player.PlayerControlGUI`; `MusicBoxAPI` is service-delegated. `api` no longer depends on `module`.
3. ~~Split `api` into its own module and publish it for downstream plugins~~ — `api` is
   now `musicbox-api/`, a standalone module with no internal dependencies (see Build).
4. Add an ArchUnit test fixing the dependency direction so it cannot regress
5. Only then consider larger domain refactors inside `module.edit`

Evaluated and kept: `MusicBox#startupAsync()` / `reloadPluginAsync()` stay in the god class. They
share the private `reloadLock`/`reloadCondition` with `MusicBoxConfig` parsing and with
`ModuleFlags` (which reads `configObject`), and `configObject` is read from hundreds of call sites
via `getConfigObject()`. Extracting the orchestration would have to drag the whole config state
along, and it is pure sequencing — no reusability to gain. The dependency-direction rule above does
not constrain it either way.

Structure changes made alongside (all behaviour-preserving):

- `MusicBoxEventHandler` (a single 339-line listener for every domain) was split into
  `core.playback.PlayerLifecycleListener` plus `module.listener.RedstoneListener`,
  `BlockInteractionListener` and `ChunkListener`. The cross-module trio is registered by
  `ModuleRuntimeSync`.
- `core.lifecycle.ModuleRuntimeSync` moved to `module.ModuleRuntimeSync` and now also owns the
  cross-module listeners.
- `MusicBox#isLoaded()` (inverted semantics) renamed to `isStartingUp()`.
- Module toggles (`isXxxModuleEnabled`, `usesXxx`) moved into `common.config.ModuleFlags`;
  `MusicBox` keeps one-line delegates.
- The `onDisable` teardown chain moved into `module.ShutdownSteps`.
