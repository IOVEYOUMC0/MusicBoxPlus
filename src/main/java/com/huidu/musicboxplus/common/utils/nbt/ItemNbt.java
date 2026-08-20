package com.huidu.musicboxplus.common.utils.nbt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.huidu.musicboxplus.MusicBox;

// Integer tags on item meta, which is the whole of what this plugin stores on an item.
//
// Was a factory plus a one-implementation interface plus a constants holder plus the
// implementation, 101 lines across four files, for two call sites.
public final class ItemNbt {

    // NamespacedKey runs regex validation per construction; cache the few keys used.
    private static final Map<String, NamespacedKey> KEY_CACHE = new ConcurrentHashMap<>();

    private ItemNbt() {
    }

    private static NamespacedKey key(String key) {
        return KEY_CACHE.computeIfAbsent(key, k -> new NamespacedKey(MusicBox.getInstance(), k));
    }

    // Writes into an already-fetched meta so the song-stack render builds one meta and sets it
    // once, rather than doing a clone plus a meta round trip per key.
    public static void set(ItemMeta meta, String key, int value) {
        meta.getPersistentDataContainer().set(key(key), PersistentDataType.INTEGER, value);
    }

    // Meta-based read for the same reason: findByItem scans chests and would otherwise clone the
    // meta once per key checked.
    public static int get(ItemMeta meta, String key) {
        Integer value = meta.getPersistentDataContainer().get(key(key), PersistentDataType.INTEGER);
        return value != null ? value : 0;
    }
}
