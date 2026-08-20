package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.StorageAccess;
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
import java.util.concurrent.TimeUnit;
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

    // Displays whose restore could not reproduce what the file described -- a song that would not
    // resolve, most often because the songs folder was momentarily unavailable. Their live handle
    // is a lesser thing than their record, so it must not be allowed to overwrite it.
    private static final Set<String> INCOMPLETE = ConcurrentHashMap.newKeySet();

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
    // Also runs on /musicboxplus reload, where every display is still alive and untouched. Rebuilding
    // them there would spawn a second set of entities and orphan the first, which nothing can then
    // remove, so live ones are left exactly as they are.
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

        int restored = 0;
        int waiting = 0;
        for (Map<String, Object> entry : snapshotRecords()) {
            String name = string(entry, "name", null);
            if (name == null || name.isBlank()) {
                continue;
            }
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
    }

    // Drops a display for good. The only thing that ever removes a record, which is what separates
    // "the operator deleted this" from every other way a display can leave the registry.
    public static void forget(String name) {
        if (name == null) {
            return;
        }
        RECORDS.remove(key(name));
        INCOMPLETE.remove(key(name));
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
        if (songs.lostAny) {
            // Its record still describes the full playlist; keep the live handle from writing the
            // reduced version back over it.
            INCOMPLETE.add(key(name));
        }
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
        boolean lostAny = false;
        Object raw = entry.get("songs");
        if (!(raw instanceof List<?> list)) {
            return new SongList(songs, false);
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
                lostAny = true;
                MusicBox.getInstance().getLogger().warning("Text display '" + displayName
                        + "' refers to a song that is not loaded: " + string(stored, "name", "?")
                        + " (kept in " + FILE_NAME + " in case it comes back)");
            }
        }
        return new SongList(songs, lostAny);
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

    private record SongList(List<MusicBoxSong> songs, boolean lostAny) {
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
        options.setBillboardFixed(bool(stored, "billboardFixed", options.isBillboardFixed()));
        options.setFixedYaw(number(stored, "fixedYaw", options.getFixedYaw()).floatValue());
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
    public static void saveSoon() {
        if (!loaded) {
            return;
        }
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(TextDisplayStore::saveIfChanged);
    }

    private static void saveIfChanged() {
        try {
            String serialized = serialize();
            if (serialized.equals(LAST_WRITTEN.get())) {
                return;
            }
            write(serialized);
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to save text displays", e);
        }
    }

    // Writes immediately. Used on disable, where it has to run before the block players are torn
    // down: a destroyed display stops being readable and its record would go out stale.
    public static void saveNow() {
        try {
            write(serialize());
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

    // Refreshes every record that has a live handle, then emits all of them. Records without a live
    // handle go out unchanged -- that is the whole point; see the class comment.
    private static String serialize() {
        for (String name : TextDisplayPlayerManager.getNames()) {
            if (INCOMPLETE.contains(key(name))) {
                continue;
            }
            TextDisplayPlayerManager.get(name).ifPresent(handle -> {
                try {
                    RECORDS.put(key(handle.getName()), serializeHandle(handle));
                } catch (Exception e) {
                    // Keeps the previous record rather than losing the display. Reading a handle can
                    // fail while its world is going away, which is exactly when the record matters.
                    MusicBox.getInstance().getLogger().log(Level.FINE,
                            "Keeping the stored copy of text display '" + name + "'", e);
                }
            });
        }
        return toYaml(snapshotRecords());
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
        options.put("billboardFixed", displayOptions.isBillboardFixed());
        options.put("fixedYaw", (double) displayOptions.getFixedYaw());
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
