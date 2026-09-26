package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.*;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.logging.Level;

public final class PlayerMusicDiscHelper {
    public static final String PLAYER_MUSIC_ID_KEY = "player_music_id";

    private PlayerMusicDiscHelper() {
    }

    public static ItemStack createDisc(PlayerMusic music) {
        return createDisc(music, null);
    }

    public static ItemStack createDisc(PlayerMusic music, PublishedMusic source) {
        GUIConfigManager.SongItemConfig songItemConfig = GUIConfigManager.getInstance().getSongItemConfig();
        ItemStack stack = new ItemStack(resolveMaterial(songItemConfig));
        stack.setAmount(1);

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String itemName = replacePlaceholders(songItemConfig.getNameFormat(), createPlaceholders(music, source));
            meta.displayName(MiniMessageUtils.processComponent(itemName));
            meta.lore(MiniMessageUtils.processComponents(createLore(songItemConfig, music, source)));
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
            if (songItemConfig.getCustomModelData() > 0) {
                meta.setCustomModelData(songItemConfig.getCustomModelData());
            }
            meta.getPersistentDataContainer().set(getMusicIdKey(), PersistentDataType.STRING, music.getUniqueId().toString());
            stack.setItemMeta(meta);
        }

        return ItemModelHelper.setItemModel(stack, songItemConfig.getItemModel());
    }

    public static boolean hasSpaceForDisc(Player player) {
        return player != null && player.getInventory().firstEmpty() >= 0;
    }

    public static boolean giveDisc(Player player, PlayerMusic music, PublishedMusic source) {
        if (player == null || music == null) {
            return true;
        }
        try {
            ItemStack disc = createDisc(music, source);
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(disc);
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
            return false;
        } catch (RuntimeException e) {
            MusicBox.getInstance().getLogger().log(Level.WARNING,
                    "Failed to give player music disc: " + music.getName(), e);
            return true;
        }
    }

    public static Optional<UUID> findMusicId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        return findMusicId(stack.getItemMeta());
    }

    // Meta-based read so callers that already resolved the meta (e.g. findByItem scanning a
    // chest) do not clone the whole ItemMeta again.
    public static Optional<UUID> findMusicId(ItemMeta meta) {
        if (meta == null) {
            return Optional.empty();
        }
        String value = meta.getPersistentDataContainer().get(getMusicIdKey(), PersistentDataType.STRING);
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<PlayerMusic> findMusic(ItemStack stack) {
        return findMusicId(stack).map(id -> PlayerMusicManager.getInstance().getMusicById(id));
    }

    private static volatile NamespacedKey musicIdKey;

    private static NamespacedKey getMusicIdKey() {
        NamespacedKey k = musicIdKey;
        if (k == null) {
            k = new NamespacedKey(MusicBox.getInstance(), PLAYER_MUSIC_ID_KEY);
            musicIdKey = k;
        }
        return k;
    }

    private static Material resolveMaterial(GUIConfigManager.SongItemConfig songItemConfig) {
        if (songItemConfig.isCustomEnabled() || !songItemConfig.isUseRandomDisc()) {
            Material customMaterial = songItemConfig.getCustomMaterial();
            return isUsableItem(customMaterial) ? customMaterial : Material.MUSIC_DISC_CAT;
        }
        List<Material> availableDiscs = songItemConfig.getAvailableDiscs();
        List<Material> usableDiscs = new ArrayList<>();
        if (availableDiscs != null) {
            for (Material disc : availableDiscs) {
                if (isUsableItem(disc)) {
                    usableDiscs.add(disc);
                }
            }
        }
        Material material = usableDiscs.isEmpty()
                ? BukkitUtils.getRandomDisc()
                : ArrayUtils.getRandom(usableDiscs);
        return isUsableItem(material) ? material : Material.MUSIC_DISC_CAT;
    }

    private static boolean isUsableItem(Material material) {
        return material != null && material != Material.AIR && material.isItem();
    }

    private static List<String> createLore(GUIConfigManager.SongItemConfig songItemConfig, PlayerMusic music, PublishedMusic source) {
        List<String> loreFormat = songItemConfig.getLoreFormat();
        if (loreFormat == null || loreFormat.isEmpty()) {
            loreFormat = new ArrayList<>();
            loreFormat.add("<gray>Author: <white>{author}");
            loreFormat.add("<gray>Length: <white>{length}");
        }
        return ArrayUtils.replaceOrRemove(new ArrayList<>(loreFormat), createPlaceholders(music, source));
    }

    private static Map<String, String> createPlaceholders(PlayerMusic music, PublishedMusic source) {
        String author = source != null && source.getAuthor() != null && !source.getAuthor().isEmpty()
                ? source.getAuthor()
                : music.getAuthor();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{song}", music.getName());
        placeholders.put("{name}", music.getName());
        placeholders.put("{author}", author);
        placeholders.put("{original_author}", author);
        placeholders.put("{length}", formatLength(music));
        placeholders.put("{notes}", String.valueOf(music.getNoteCount()));
        placeholders.put("{bpm}", String.valueOf(music.getBpm()));
        placeholders.put("{timeSignature}", music.getTimeSignature().toString());
        placeholders.put("{description}", music.getDescription());
        placeholders.put("{aliases}", null);
        placeholders.put("{tags}", null);
        return placeholders;
    }

    private static String replacePlaceholders(String value, Map<String, String> placeholders) {
        if (value == null) {
            return "";
        }
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace(entry.getKey(), replacement);
        }
        return result;
    }

    private static String formatLength(PlayerMusic music) {
        int ticksPerSecond = Math.max(1, music.getBpm() * Math.max(1, music.getBeatSubdivision()) / 60);
        int seconds = Math.max(0, music.getMaxTick()) / ticksPerSecond;
        return StringUtils.toHumanTime(seconds);
    }
}
