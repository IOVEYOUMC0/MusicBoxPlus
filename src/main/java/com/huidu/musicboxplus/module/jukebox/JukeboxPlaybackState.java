package com.huidu.musicboxplus.module.jukebox;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Whether a jukebox holding a disc is supposed to be playing, as of the last time the plugin saw it.
//
// A restart leaves nothing behind but "the block holds a MusicBox disc", and the restore paths (the
// startup scan in MusicBox#restoreJukeboxesInLoadedChunks and the ChunkLoadEvent handler) used to
// read that as "should be playing". Take a jukebox whose song had already reached its end and reload
// the plugin -- /reload, PlugMan, or a server restart -- and the disc started over. Vanilla never
// does that: a loaded jukebox with a disc in it is silent until a player clicks it. A disc the
// player had paused or stopped came back playing the same way.
//
// So the "not playing" state has to be remembered. It is one line per jukebox that ever stopped, it
// is written on state transitions rather than per tick, and it is keyed by block position AND song
// hash: taking this disc out and later inserting a different one must not inherit the mark.
//
// Unknown means resume. A jukebox the plugin has no record of (a fresh install, or a disc another
// plugin put in) keeps the old behaviour of starting on its own, so this file only ever suppresses
// playback that the plugin watched stop.
public final class JukeboxPlaybackState {

    private static final String FILE_NAME = "jukebox-resume.yml";
    private static final String ROOT = "jukeboxes";

    private static final Index STATE = new Index();
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean();
    private static volatile boolean dirty;

    private JukeboxPlaybackState() {
    }

    // ==================== Runtime state ====================

    // The disc at this block finished, or was paused/stopped: the restore paths must leave it alone.
    public static void markNotPlaying(Location location, int songHash) {
        if (location == null || songHash == -1) {
            return;
        }
        if (STATE.markNotPlaying(key(location), songHash)) {
            changed();
        }
    }

    // Playback started at this block, whatever the previous state was.
    public static void markPlaying(Location location) {
        if (location != null && STATE.markPlaying(key(location))) {
            changed();
        }
    }

    // Whether restore should start playback for the disc now in this block.
    public static boolean shouldResume(Location location, int songHash) {
        return location != null && STATE.shouldResume(key(location), songHash);
    }

    // ==================== Persistence ====================

    // Called once from onEnable, before the startup restore scan can ask any question.
    public static void load() {
        synchronized (JukeboxPlaybackState.class) {
            STATE.clear();
            dirty = false;
            File file = file();
            if (!file.isFile()) {
                return;
            }
            try {
                List<Map<String, Object>> records = fromYaml(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                for (Map<String, Object> record : records) {
                    String world = string(record, "world", null);
                    if (world == null || world.isEmpty()) {
                        continue;
                    }
                    int song = number(record, "song", -1).intValue();
                    if (song == -1) {
                        continue;
                    }
                    STATE.markNotPlaying(key(world,
                            number(record, "x", 0).intValue(),
                            number(record, "y", 0).intValue(),
                            number(record, "z", 0).intValue()), song);
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                // Nothing is suppressed this session, and nothing is written until the next state
                // change -- so the unreadable file is preserved rather than replaced by an empty
                // one, and a hand edit can still be repaired.
                MusicBox.getInstance().getLogger().warning(
                        "Could not read " + FILE_NAME + "; stopped jukeboxes may resume playback: " + e.getMessage());
            }
            dirty = false;
        }
    }

    // Called from onDisable, and from the debounced save after a state change.
    public static void saveIfDirty() {
        synchronized (JukeboxPlaybackState.class) {
            if (!dirty) {
                return;
            }
            dirty = false;
            write(toRecords(STATE));
        }
    }

    // Takes the index rather than reading the static one, so the on-disk shape can be checked
    // without touching the singleton the server owns.
    static List<Map<String, Object>> toRecords(Index index) {
        List<Map<String, Object>> records = new ArrayList<>(index.size());
        for (Map.Entry<String, Integer> entry : index.entries()) {
            String[] parts = entry.getKey().split(";", 4);
            if (parts.length != 4) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("world", parts[0]);
            record.put("x", Integer.parseInt(parts[1]));
            record.put("y", Integer.parseInt(parts[2]));
            record.put("z", Integer.parseInt(parts[3]));
            record.put("song", entry.getValue());
            records.add(record);
        }
        return records;
    }

    // Debounced: a song can end on a region thread, and the state it flips is not worth a disk write
    // per song. The pending save is not lost on shutdown -- onDisable calls saveIfDirty() directly.
    private static void changed() {
        dirty = true;
        if (!SAVE_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            Scheduler.asyncLater(() -> {
                SAVE_SCHEDULED.set(false);
                saveIfDirty();
            }, 5, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Async executor already shut down (a disable is in progress); onDisable's own save
            // covers this. Not an error: the in-memory state is authoritative until then.
            SAVE_SCHEDULED.set(false);
        }
    }

    private static void write(List<Map<String, Object>> records) {
        File file = file();
        try {
            Path directory = file.getParentFile().toPath();
            Files.createDirectories(directory);
            Path temp = file.toPath().resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, toYaml(records), StandardCharsets.UTF_8);
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            MusicBox.getInstance().getLogger().warning(
                    "Could not write " + FILE_NAME + "; stopped jukeboxes may resume after a restart: " + e.getMessage());
        }
    }

    private static File file() {
        return new File(MusicBox.getInstance().getDataFolder(), FILE_NAME);
    }

    // ==================== Format ====================
    // Split out from the file and registry plumbing so the in-memory rules and the on-disk shape can
    // be checked without a server: getting either wrong only shows up on the next restart, when the
    // marks are already gone.

    static String toYaml(List<Map<String, Object>> records) {
        YamlConfiguration config = new YamlConfiguration();
        config.options().setHeader(List.of(
                "Jukeboxes that the plugin last saw NOT playing (finished, paused or stopped).",
                "Restored playback after a restart or reload skips these, so a disc that had already",
                "played to its end does not start over. Remove a line to let that jukebox resume.",
                "",
                "Written by the plugin; edit it with the server STOPPED."));
        config.set(ROOT, records);
        return config.saveToString();
    }

    static List<Map<String, Object>> fromYaml(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList(ROOT)) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    record.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            records.add(record);
        }
        return records;
    }

    static String key(String world, int x, int y, int z) {
        return world + ';' + x + ';' + y + ';' + z;
    }

    private static String key(Location location) {
        World world = location.getWorld();
        return key(world != null ? world.getName() : "", location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String string(Map<String, Object> record, String key, String fallback) {
        Object value = record.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Number number(Map<String, Object> record, String key, Number fallback) {
        Object value = record.get(key);
        return value instanceof Number number ? number : fallback;
    }

    // The rules, without a Location or a plugin instance: tests drive this directly.
    static final class Index {

        private final Map<String, Integer> notPlaying = new ConcurrentHashMap<>();

        // True when the mark was new, i.e. the caller should schedule a save.
        boolean markNotPlaying(String key, int songHash) {
            return notPlaying.put(key, songHash) == null;
        }

        // True when a mark was actually removed.
        boolean markPlaying(String key) {
            return notPlaying.remove(key) != null;
        }

        boolean shouldResume(String key, int songHash) {
            Integer marked = notPlaying.get(key);
            if (marked == null) {
                return true;
            }
            if (marked.intValue() != songHash) {
                // Stale: another disc is in this block now, so the old mark must not suppress it.
                notPlaying.remove(key, marked);
                return true;
            }
            return false;
        }

        int size() {
            return notPlaying.size();
        }

        void clear() {
            notPlaying.clear();
        }

        java.util.Set<Map.Entry<String, Integer>> entries() {
            return notPlaying.entrySet();
        }
    }
}
