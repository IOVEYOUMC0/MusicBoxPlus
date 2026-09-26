package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

// On-disk record of every placed text display, in text-displays.yml.
//
// The floating entities are spawned with setPersistent(false), so Minecraft never writes them to
// the world: that keeps a crashed or downgraded server from leaving undeletable displays behind,
// but it also means the running server is the only thing that remembers a display exists. Without
// this file every text display is gone after a restart.
//
// A file rather than the database: a display is a placed fixture, not runtime state. There are a
// handful of them, they change only when somebody edits one, their shape is nested rather than
// tabular, and an operator may reasonably want to read, fix or copy them by hand -- including
// deleting one whose world no longer exists. This also survives switching between sqlite and
// mysql, which the displays have no reason to care about.
//
// THE CENTRAL RULE: this class, not the live registry, is what the file is written from.
//
// Deriving the file from whatever TextDisplayPlayerManager currently holds looks equivalent and is
// not, because a display leaves that registry for many reasons that are not "the operator deleted
// it": its world was unloaded, its module was switched off, its songs failed to load, the server
// never got as far as restoring it. Every one of those would rewrite the file without it, and
// since the file is the only copy, the display would be gone for good. So each display keeps a
// record here, a record is refreshed from a live handle but never dropped by one, and only
// forget() -- called from an explicit delete -- removes it.
//
// Displays are stored as a LIST rather than keyed by name. Names come from a command argument and
// are not validated, so a name containing a dot would be read back as a nested path and the
// display would quietly vanish into a section with no fields.
public final class TextDisplayStore {

    private static final String FILE_NAME = "text-displays.yml";
    private static final String ROOT = "displays";

    // Authoritative state, keyed by normalised name and kept in file order.
    private static final Map<String, Map<String, Object>> RECORDS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // Names between "decided to restore" and "inserted into the registry". Restore hops onto each
    // display's own region thread, so without this a second restoreAll could look at the registry,
    // still see nothing, and spawn a second set of entities for the same display.
    private static final Set<String> RESTORING = ConcurrentHashMap.newKeySet();

    // Nothing may be written before the file has been read. Otherwise a server that never reached
    // restoreAll -- module off, failed startup, unreadable file -- would save its empty state over
    // the real one on shutdown.
    private static volatile boolean loaded = false;

    // Last serialisation written, so the periodic save is a no-op while nothing changes.
    private static final AtomicReference<String> LAST_WRITTEN = new AtomicReference<>();

    private static volatile boolean backedUpThisSession = false;
    private static volatile boolean warnedUnwritable = false;
    private static volatile MbTask autoSaveTask;
    private static volatile boolean saveClosed = false;
    private static CompletableFuture<Void> saveChain = CompletableFuture.completedFuture(null);
    private static final AtomicLong saveGeneration = new AtomicLong();

    private TextDisplayStore() {
    }

    private static File file() {
        return new File(MusicBox.getInstance().getDataFolder(), FILE_NAME);
    }

    private static String key(String name) {
        return name.trim().toLowerCase();
    }

    // Reads the file and recreates every display that is not already live.
    //
    // Also runs on /musicboxplus reload. Existing displays are rebound in place so they keep one
    // set of entities while their playlists follow the freshly loaded song objects.
    public static void restoreAll() {
        List<Map<String, Object>> stored;
        try {
            stored = read();
        } catch (Exception e) {
            // Deliberately leaves loaded == false: an unreadable file is a file an operator can
            // still fix by hand, and saving over it would destroy that chance.
            MusicBox.getInstance().getLogger().log(Level.SEVERE, "Could not read " + FILE_NAME
                    + "; text displays will not be restored and the file will NOT be overwritten", e);
            return;
        }

        for (Map<String, Object> entry : stored) {
            String name = string(entry, "name", null);
            if (name != null && !name.isBlank()) {
                RECORDS.putIfAbsent(key(name), entry);
            }
        }
        loaded = true;

        // A song reload rebuilds every MusicBoxSong instance. Rebind live text players to the new
        // instances before restoring records, and drop entries that no longer resolve.
        pruneLivePlaylists();

        int restored = 0;
        int waiting = 0;
        for (Map<String, Object> entry : snapshotRecords()) {
            String name = string(entry, "name", null);
            if (name == null || name.isBlank()) {
                continue;
            }
            // Clean song references even when the display's world is not mounted yet.
            SongList storedSongs = readSongs(entry, name);
            normalizePlaylistEntry(entry, storedSongs.songs);
            if (TextDisplayPlayerManager.get(name).isPresent() || !RESTORING.add(key(name))) {
                continue;
            }
            World world = MusicBox.getInstance().getServer().getWorld(string(entry, "world", ""));
            if (world == null) {
                // The record stays; a world a multiverse-style plugin mounts later, or one that is
                // temporarily unavailable, must not cost the operator their display.
                RESTORING.remove(key(name));
                waiting++;
                continue;
            }
            try {
                restore(name, world, entry);
                restored++;
            } catch (Exception e) {
                RESTORING.remove(key(name));
                MusicBox.getInstance().getLogger().log(Level.WARNING,
                        "Failed to restore text display '" + name + "'", e);
            }
        }

        if (restored > 0 || waiting > 0) {
            MusicBox.getInstance().getLogger().info("Restored " + restored + " text display(s)"
                    + (waiting > 0 ? ", " + waiting + " waiting for their world to load" : ""));
        }
        saveSoon();
    }

    // Drops a display for good. The only thing that ever removes a record, which is what separates
    // "the operator deleted this" from every other way a display can leave the registry.
    public static void forget(String name) {
        if (name == null) {
            return;
        }
        synchronized (RECORDS) {
            RECORDS.remove(key(name));
        }
        saveSoon();
    }

    private static List<Map<String, Object>> snapshotRecords() {
        synchronized (RECORDS) {
            return new ArrayList<>(RECORDS.values());
        }
    }

    private static List<Map<String, Object>> read() throws IOException, InvalidConfigurationException {
        File file = file();
        if (!file.isFile()) {
            return List.of();
        }
        return fromYaml(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    // Pure format handling, split out from the file and registry plumbing so the on-disk shape can
    // be tested on its own -- it is the part whose failure mode only shows up on the next restart,
    // when it is too late.
    static List<Map<String, Object>> fromYaml(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList(ROOT)) {
            Map<String, Object> entry = map(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static String toYaml(List<Map<String, Object>> entries) {
        YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Text displays placed on this server.",
                "The floating entities are not saved with the world, so this file is the only record",
                "that they exist.",
                "",
                "MusicBox rewrites this file while it runs, so edit it with the server STOPPED --",
                "changes made to it in the meantime are overwritten on the next save."));
        config.set(ROOT, entries);
        return config.saveToString();
    }

    private static void restore(String name, World world, Map<String, Object> entry) {
        Location location = new Location(world,
                number(entry, "x", 0).doubleValue(),
                number(entry, "y", 0).doubleValue(),
                number(entry, "z", 0).doubleValue());
        int range = number(entry, "range", TextDisplayPlayerManager.MIN_RANGE).intValue();
        float speed = number(entry, "speed", 1).floatValue();
        TextDisplayPlayer.DisplayOptions options = readOptions(map(entry.get("options")));
        SongList songs = readSongs(entry, name);
        boolean playing = bool(entry, "playing", true);

        // Each display belongs to whichever region owns its block, and restore runs on the global
        // thread, so hop over before spawning entities. The chunk has to be resident first or the
        // spawn lands in an unloaded chunk.
        //
        // Routed through whenReady rather than straight to the region: every restored display
        // would otherwise build its arrangement on a region thread at startup, one whole-file
        // read and parse each, all while the server is coming up.
        IPlayList prepared = songs.songs.isEmpty() ? null : buildPlaylist(songs.songs, entry);
        com.huidu.musicboxplus.core.player.PlaybackSetup.whenReady(prepared,
                run -> Scheduler.region(location, run),
                () -> restoreOnRegion(name, location, range, speed, options, prepared, playing, entry));
    }

    // Runs on the region that owns the display's block, with the arrangement already built.
    private static void restoreOnRegion(String name, Location location, int range, float speed,
                                        TextDisplayPlayer.DisplayOptions options, IPlayList prepared,
                                        boolean playing, Map<String, Object> entry) {
        try {
            location.getChunk().load();
            if (prepared == null) {
                TextDisplayPlayerManager.restoreIdle(name, location, range, options);
                return;
            }
            TextDisplayPlayer player = TextDisplayPlayerManager.restoreActive(name, prepared, location,
                    range, speed, options, parseLoop(string(entry, "loop", null), prepared.hasNext()));
            if (!playing) {
                // The base constructor starts every block player, so a display the operator had
                // paused would come back audible.
                player.setPlaying(false);
            }
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING,
                    "Failed to spawn restored text display '" + name + "'", e);
        } finally {
            RESTORING.remove(key(name));
            // No saveSoon() here. restoreAll() already saves once after its loop, and every save
            // captures EVERY live display on its region thread and re-serialises the whole file --
            // so a per-display save turned restoring N displays into N+1 full passes over N
            // displays (N^2 region tasks) for a file that only changes once, at the end.
        }
    }

    private static IPlayList buildPlaylist(List<MusicBoxSong> songs, Map<String, Object> entry) {
        if (songs.size() == 1) {
            return new SingletonPlayList(songs.get(0));
        }
        ListPlaylist list = new ListPlaylist(songs, bool(entry, "hasEnd", false));
        // By identity, not by ordinal: a song that failed to resolve shifts every later index, so a
        // stored position would silently select the wrong track.
        MusicBoxSong current = resolveSong(map(entry.get("current")));
        if (current != null && songs.contains(current)) {
            list.setSong(current);
            return list;
        }
        int index = Math.max(0, Math.min(number(entry, "currentIndex", 0).intValue(), songs.size() - 1));
        list.setSong(songs.get(index));
        return list;
    }

    // Songs are stored by hash and by name. The hash is the identity the rest of the plugin uses
    // and is tried first; the name is the fallback, because the hash is derived from the file path
    // and every display would otherwise lose its song the first time the songs folder moves.
    private static SongList readSongs(Map<String, Object> entry, String displayName) {
        List<MusicBoxSong> songs = new ArrayList<>();
        Object raw = entry.get("songs");
        if (!(raw instanceof List<?> list)) {
            return new SongList(songs);
        }
        for (Object element : list) {
            Map<String, Object> stored = map(element);
            if (stored == null) {
                continue;
            }
            MusicBoxSong resolved = resolveSong(stored);
            if (resolved != null) {
                songs.add(resolved);
            } else {
                MusicBox.getInstance().getLogger().warning("Text display '" + displayName
                        + "' refers to a song that is not loaded; removing it from the playlist: "
                        + string(stored, "name", "?"));
            }
        }
        return new SongList(songs);
    }

    private static MusicBoxSong resolveSong(Map<String, Object> stored) {
        if (stored == null) {
            return null;
        }
        MusicBoxSong resolved = stored.get("hash") instanceof Number hash
                ? MusicBoxSongManager.findSongByHash(hash.intValue()).orElse(null)
                : null;
        String name = string(stored, "name", null);
        if (resolved == null && name != null) {
            resolved = MusicBoxSongManager.findByName(name).orElse(null);
        }
        return resolved;
    }

    private record SongList(List<MusicBoxSong> songs) {
    }

    private static void pruneLivePlaylists() {
        for (String name : TextDisplayPlayerManager.getNames()) {
            TextDisplayPlayer player = TextDisplayPlayerManager.getActive(name).orElse(null);
            if (player == null) {
                continue;
            }
            IPlayList oldList = player.getPlayList();
            List<MusicBoxSong> rebound = new ArrayList<>();
            MusicBoxSong oldCurrent = oldList == null ? null : (MusicBoxSong) oldList.getCurrent();
            int currentIndex = 0;
            boolean hasEnd = false;
            boolean changed = false;
            if (oldList instanceof ListPlaylist list) {
                hasEnd = list.hasEnd();
                List<MusicBoxSong> oldSongs = list.getSongsSnapshot();
                for (MusicBoxSong oldSong : oldSongs) {
                    MusicBoxSong resolved = resolveSong(songEntry(oldSong));
                    if (resolved != oldSong) {
                        changed = true;
                    }
                    if (resolved != null) {
                        if (oldSong == oldCurrent) {
                            currentIndex = rebound.size();
                        }
                        rebound.add(resolved);
                    }
                }
            } else if (oldCurrent != null) {
                MusicBoxSong resolved = resolveSong(songEntry(oldCurrent));
                changed = resolved != oldCurrent;
                if (resolved != null) {
                    rebound.add(resolved);
                }
            }
            if (!changed) {
                continue;
            }
            Location location = player.getLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            IPlayList replacement = rebound.isEmpty() ? null : rebound.size() == 1
                    ? new SingletonPlayList(rebound.get(0))
                    : new ListPlaylist(rebound, hasEnd);
            if (replacement instanceof ListPlaylist list && !rebound.isEmpty()) {
                list.setSong(rebound.get(Math.min(currentIndex, rebound.size() - 1)));
            }
            boolean wasPlaying = player.isPlaying();
            Scheduler.region(location, () -> {
                if (TextDisplayPlayerManager.get(name).orElse(null) != player) {
                    return;
                }
                if (replacement == null) {
                    TextDisplayPlayerManager.replaceWithIdle(name);
                } else if (TextDisplayPlayerManager.setPlaylist(name, replacement) && !wasPlaying) {
                    TextDisplayPlayerManager.getActive(name).ifPresent(updated -> updated.setPlaying(false));
                }
            });
        }
    }

    private static void normalizePlaylistEntry(Map<String, Object> entry, List<MusicBoxSong> songs) {
        List<Map<String, Object>> storedSongs = new ArrayList<>(songs.size());
        for (MusicBoxSong song : songs) {
            storedSongs.add(songEntry(song));
        }
        entry.put("songs", storedSongs);
        MusicBoxSong current = resolveSong(map(entry.get("current")));
        if (current != null && songs.contains(current)) {
            entry.put("current", songEntry(current));
            entry.put("currentIndex", songs.indexOf(current));
        } else {
            entry.remove("current");
            if (songs.isEmpty()) {
                entry.remove("currentIndex");
            } else {
                int index = Math.max(0, Math.min(number(entry, "currentIndex", 0).intValue(), songs.size() - 1));
                entry.put("currentIndex", index);
            }
        }
    }

    private static TextDisplayPlayer.DisplayOptions readOptions(Map<String, Object> stored) {
        TextDisplayPlayer.DisplayOptions options = TextDisplayPlayer.DisplayOptions.defaults();
        if (stored == null) {
            return options;
        }
        options.setShowName(bool(stored, "showName", options.isShowName()));
        options.setShowSong(bool(stored, "showSong", options.isShowSong()));
        options.setShowProgress(bool(stored, "showProgress", options.isShowProgress()));
        options.setShowTime(bool(stored, "showTime", options.isShowTime()));
        options.setHeightOffset(number(stored, "heightOffset", options.getHeightOffset()).doubleValue());
        options.setXOffset(number(stored, "xOffset", options.getXOffset()).doubleValue());
        options.setZOffset(number(stored, "zOffset", options.getZOffset()).doubleValue());
        options.setBillboardFixed(bool(stored, "billboardFixed", options.isBillboardFixed()));
        options.setFixedYaw(number(stored, "fixedYaw", options.getFixedYaw()).floatValue());
        options.setDoubleSided(bool(stored, "doubleSided", options.isDoubleSided()));
        options.setAllowPublicEdit(bool(stored, "allowPublicEdit", options.isAllowPublicEdit()));
        return options;
    }

    private static LoopMode parseLoop(String stored, boolean multiSong) {
        if (stored != null) {
            try {
                return LoopMode.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // Falls through to the default below
            }
        }
        return multiSong ? LoopMode.ALL : LoopMode.SINGLE;
    }

    // Periodic save.
    //
    // Display options are edited straight on the object handed out by getDisplayOptions(), and
    // height and billboard tweaks go through the visual, so there is no single mutation point to
    // hook. Writing on a timer and skipping identical content covers every edit path without asking
    // each one to remember to persist itself; the structural changes (create, delete, move, new
    // playlist) additionally call saveSoon so they are not left to the timer.
    public static void startAutoSave(long intervalSeconds) {
        stopAutoSave();
        saveClosed = false;
        saveGeneration.incrementAndGet();
        autoSaveTask = Scheduler.asyncTimer(TextDisplayStore::saveIfChanged,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public static void stopAutoSave() {
        MbTask task = autoSaveTask;
        if (task != null) {
            task.cancel();
            autoSaveTask = null;
        }
    }

    // Fire-and-forget save for a change that must not wait for the next tick of the timer.
    //
    // Coalesced: one queued save covers every caller until it starts, because a save is a full
    // capture of every display plus a whole-file serialisation. Creating or moving a batch of
    // displays used to queue one of those per change. The flag is cleared BEFORE the save runs, so
    // a change made while a save is in flight still queues the next one instead of being lost.
    private static final java.util.concurrent.atomic.AtomicBoolean SAVE_QUEUED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void saveSoon() {
        if (!loaded || saveClosed) {
            return;
        }
        if (!SAVE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            SAVE_QUEUED.set(false);
            saveIfChanged();
        });
    }

    private static synchronized void saveIfChanged() {
        if (!loaded || saveClosed) {
            return;
        }
        long generation = saveGeneration.get();
        // Queue saves so a slower region snapshot cannot finish after a newer one and overwrite it.
        saveChain = saveChain.handle((ignored, failure) -> null)
                .thenCompose(ignored -> serializeAsync())
                .thenAccept(serialized -> writeIfChanged(serialized, generation))
                .exceptionally(failure -> {
                    MusicBox.getInstance().getLogger().log(Level.WARNING,
                            "Failed to save text displays", failure);
                    return null;
                });
    }

    // Writes immediately. Used on disable, where it has to run before the block players are torn
    // down. Folia's global thread cannot synchronously read every region, so use the last complete
    // records captured while the plugin was running instead of blocking or reading foreign state.
    // ponytail: a capture still in flight is left to the next normal save; waiting here can deadlock.
    public static synchronized void saveNow() {
        saveClosed = true;
        saveGeneration.incrementAndGet();
        try {
            write(toYaml(snapshotRecords()));
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to save text displays", e);
        }
    }

    private static synchronized void writeIfChanged(String serialized, long generation) {
        if (saveClosed || generation != saveGeneration.get() || serialized.equals(LAST_WRITTEN.get())) {
            return;
        }
        try {
            write(serialized);
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to save text displays", e);
        }
    }

    private static synchronized void write(String serialized) throws IOException {
        if (!loaded) {
            return;
        }
        File file = file();
        if (!StorageAccess.canWriteTo(file)) {
            if (!warnedUnwritable) {
                warnedUnwritable = true;
                MusicBox.getInstance().getLogger().warning("Cannot write " + FILE_NAME
                        + "; text displays created this session will be lost on restart");
            }
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }

        // One backup per session, taken before the first overwrite, so a bad restore or a bad hand
        // edit is recoverable at all.
        if (!backedUpThisSession && file.isFile()) {
            backedUpThisSession = true;
            try {
                Files.copy(file.toPath(), new File(file.getParentFile(), FILE_NAME + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                MusicBox.getInstance().getLogger().log(Level.WARNING,
                        "Could not back up " + FILE_NAME, e);
            }
        }

        // Through a temporary file: a crash or a full disk partway through a direct write leaves a
        // truncated file, and a truncated file is indistinguishable from "there are no displays".
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        Files.writeString(temp.toPath(), serialized, StandardCharsets.UTF_8);
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        LAST_WRITTEN.set(serialized);
    }

    // Captures each live display on the region that owns it, then serialises the merged records off
    // those region threads. Records without a live handle go out unchanged -- that is the whole
    // point; see the class comment.
    private static CompletableFuture<String> serializeAsync() {
        List<HandleCapture> captures = new ArrayList<>();
        for (String name : TextDisplayPlayerManager.getNames()) {
            TextDisplayPlayerManager.get(name).ifPresent(handle -> {
                CompletableFuture<Map<String, Object>> capture = new CompletableFuture<>();
                captures.add(new HandleCapture(name, handle, capture));
                captureHandle(name, handle, capture);
            });
        }
        if (captures.isEmpty()) {
            return CompletableFuture.completedFuture(toYaml(snapshotRecords()));
        }
        CompletableFuture<?>[] futures = captures.stream()
                .map(HandleCapture::result)
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(futures).thenApplyAsync(ignored -> {
            for (HandleCapture capture : captures) {
                Map<String, Object> entry = capture.result().join();
                if (entry != null) {
                    // The handle may have been deleted/replaced while its region snapshot was in
                    // flight. Never let that stale snapshot resurrect the old record.
                    synchronized (RECORDS) {
                        if (TextDisplayPlayerManager.get(capture.name()).orElse(null) == capture.handle()) {
                            RECORDS.put(key(capture.name()), entry);
                        }
                    }
                }
            }
            return toYaml(snapshotRecords());
        }, AsyncTaskManager.getInstance().getAsyncExecutor());
    }

    private record HandleCapture(String name, TextDisplayHandle handle,
                                 CompletableFuture<Map<String, Object>> result) {
    }

    private static void captureHandle(String name, TextDisplayHandle handle,
                                      CompletableFuture<Map<String, Object>> result) {
        Location location;
        try {
            location = handle.getLocation();
        } catch (Exception e) {
            keepStoredRecord(name, e, result);
            return;
        }
        if (location == null || location.getWorld() == null) {
            result.complete(null);
            return;
        }
        try {
            Scheduler.region(location, () -> {
                if (result.isDone()) {
                    return;
                }
                try {
                    if (TextDisplayPlayerManager.get(name).orElse(null) != handle) {
                        result.complete(null);
                        return;
                    }
                    Location current = handle.getLocation();
                    if (current == null || current.getWorld() == null) {
                        result.complete(null);
                    } else if (!Scheduler.ownsRegion(current)) {
                        // The display moved between routing and execution; retry against its new owner.
                        captureHandle(name, handle, result);
                    } else {
                        result.complete(serializeHandle(handle));
                    }
                } catch (Exception e) {
                    keepStoredRecord(name, e, result);
                }
            });
        } catch (Exception e) {
            keepStoredRecord(name, e, result);
        }
    }

    private static void keepStoredRecord(String name, Exception error,
                                          CompletableFuture<Map<String, Object>> result) {
        MusicBox.getInstance().getLogger().log(Level.FINE,
                "Keeping the stored copy of text display '" + name + "'", error);
        result.complete(null);
    }

    private static Map<String, Object> serializeHandle(TextDisplayHandle handle) {
        Map<String, Object> entry = new LinkedHashMap<>();
        Location location = handle.getLocation();
        entry.put("name", handle.getName());
        entry.put("world", location.getWorld() == null ? "" : location.getWorld().getName());
        entry.put("x", location.getX());
        entry.put("y", location.getY());
        entry.put("z", location.getZ());
        entry.put("range", handle.getRange());

        if (handle instanceof TextDisplayPlayer player) {
            entry.put("speed", (double) player.getMusicBoxModel().getPlaybackSpeedMultiplier());
            entry.put("loop", player.getMusicBoxModel().getLoopMode().name());
            entry.put("playing", player.isPlaying());
            serializePlaylist(entry, player.getMusicBoxModel().getPlayList());
        } else {
            // An idle placeholder has no playlist; an empty song list is what restores it as one.
            entry.put("songs", List.of());
        }

        Map<String, Object> options = new LinkedHashMap<>();
        TextDisplayPlayer.DisplayOptions displayOptions = handle.getDisplayOptions();
        options.put("showName", displayOptions.isShowName());
        options.put("showSong", displayOptions.isShowSong());
        options.put("showProgress", displayOptions.isShowProgress());
        options.put("showTime", displayOptions.isShowTime());
        options.put("heightOffset", displayOptions.getHeightOffset());
        options.put("xOffset", displayOptions.getXOffset());
        options.put("zOffset", displayOptions.getZOffset());
        options.put("billboardFixed", displayOptions.isBillboardFixed());
        options.put("fixedYaw", (double) displayOptions.getFixedYaw());
        options.put("doubleSided", displayOptions.isDoubleSided());
        options.put("allowPublicEdit", displayOptions.isAllowPublicEdit());
        entry.put("options", options);
        return entry;
    }

    private static void serializePlaylist(Map<String, Object> entry, IPlayList list) {
        List<Map<String, Object>> songs = new ArrayList<>();
        if (list instanceof ListPlaylist listPlaylist) {
            for (MusicBoxSong song : listPlaylist.getSongsSnapshot()) {
                songs.add(songEntry(song));
            }
            entry.put("currentIndex", listPlaylist.getCurrentIndex());
            entry.put("hasEnd", listPlaylist.hasEnd());
        } else if (list != null && list.getCurrent() != null) {
            songs.add(songEntry((MusicBoxSong) list.getCurrent()));
        }
        if (list != null && list.getCurrent() != null) {
            entry.put("current", songEntry((MusicBoxSong) list.getCurrent()));
        }
        entry.put("songs", songs);
    }

    private static Map<String, Object> songEntry(MusicBoxSong song) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", song.getName());
        entry.put("hash", song.getHash());
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : ((Map<Object, Object>) raw).entrySet()) {
            if (field.getKey() != null) {
                result.put(field.getKey().toString(), field.getValue());
            }
        }
        return result;
    }

    private static String string(Map<String, Object> entry, String key, String fallback) {
        Object value = entry.get(key);
        return value == null ? fallback : value.toString();
    }

    private static Number number(Map<String, Object> entry, String key, Number fallback) {
        return entry.get(key) instanceof Number value ? value : fallback;
    }

    private static boolean bool(Map<String, Object> entry, String key, boolean fallback) {
        return entry.get(key) instanceof Boolean value ? value : fallback;
    }
}
