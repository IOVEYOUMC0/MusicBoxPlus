package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.JukeboxSong;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Reads and writes the jukebox_playable data component.
// Whether a jukebox makes a sound depends entirely on the item carrying this component: add it
// and vanilla plays, remove it and vanilla stays quiet. MusicBox removes it to suppress vanilla
// music while driving playback itself.
//
// The API is called directly instead of via reflection: a reflective lookup that misses only
// fails silently, so suppression would stop working without a trace, while a direct call breaks
// at compile time when a signature changes.
public final class JukeboxPlayableHelper {

    // Song keys already warned about, so one bad key does not spam the log once per disc
    private static final Set<String> WARNED_UNKNOWN_SONGS = ConcurrentHashMap.newKeySet();
    // Resolved registry entries per key. RegistryAccess.getRegistry is not free and the same
    // few keys repeat on every song-stack render; the entries themselves are stable for the
    // server's lifetime, so the lookup only has to happen once per key.
    private static final Map<String, JukeboxSong> RESOLVED_SONGS = new ConcurrentHashMap<>();

    private JukeboxPlayableHelper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // Always true: the compile target is pinned to a Paper version that ships the component.
    // Kept only so existing callers keep working.
    public static boolean isSupported() {
        return true;
    }

    // Writes the jukebox_playable component. songKey must name a registered jukebox_song (vanilla
    // or datapack-provided), not an arbitrary sound event; an unknown key leaves the item untouched
    // and warns once. Returns the same item instance so calls can be chained.
    public static ItemStack setJukeboxPlayable(ItemStack item, String songKey) {
        if (item == null || songKey == null || songKey.isBlank()) {
            return item;
        }
        NamespacedKey key = NamespacedKey.fromString(songKey.trim());
        if (key == null) {
            warnOnce(songKey, "不是合法的命名空间键");
            return item;
        }
        // Goes through RegistryAccess rather than Registry.JUKEBOX_SONG, deprecated since 1.21
        Registry<JukeboxSong> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.JUKEBOX_SONG);
        JukeboxSong song = RESOLVED_SONGS.computeIfAbsent(songKey, k -> {
            NamespacedKey resolved = NamespacedKey.fromString(k);
            return resolved == null ? null : registry.get(resolved);
        });
        if (song == null) {
            warnOnce(songKey, "在 jukebox_song 注册表里不存在（需要由数据包注册）");
            return item;
        }
        item.setData(DataComponentTypes.JUKEBOX_PLAYABLE, JukeboxPlayable.jukeboxPlayable(song));
        return item;
    }

    // Puts the jukebox_playable component back to the item type's default.
    //
    // Removing the component records an explicit "absent" patch that travels with the item, so
    // a disc that was silenced this way stays silent in a vanilla jukebox and reads zero on a
    // comparator even after it leaves. Resetting drops the patch, which is not the same as
    // setting the component: an ordinary music disc gets its own song back, and an item that
    // never had one stays without.
    public static void restoreJukeboxPlayable(ItemStack item) {
        if (item == null) {
            return;
        }
        item.resetData(DataComponentTypes.JUKEBOX_PLAYABLE);
    }

    // Removes the jukebox_playable component, modifying the given item in place.
    // unsetData is required: it records a "component removed" patch. Clearing the value on the
    // ItemMeta only drops an explicit override, leaving the item type's default component in
    // effect, which means it does nothing at all for vanilla music discs.
    public static void removeJukeboxPlayable(ItemStack item) {
        if (item == null) {
            return;
        }
        item.unsetData(DataComponentTypes.JUKEBOX_PLAYABLE);
    }

    // Whether a vanilla jukebox would still play this item
    public static boolean isVanillaPlayable(ItemStack item) {
        return item != null && item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE);
    }

    private static void warnOnce(String songKey, String why) {
        if (!WARNED_UNKNOWN_SONGS.add(songKey)) {
            return;
        }
        MusicBox plugin = MusicBox.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning("song-aliases.yml 里的 jukebox-playable 值 \"" + songKey + "\" " + why
                    + "，该唱片不会获得原版可播放标记。");
        }
    }
}
