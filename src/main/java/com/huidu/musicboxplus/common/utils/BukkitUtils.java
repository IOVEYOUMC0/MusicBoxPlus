package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.metadata.Metadatable;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public final class BukkitUtils {
    public static final List<Material> DISCS = Collections.unmodifiableList(Arrays.asList(Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS, Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL, Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD, Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_11, Material.MUSIC_DISC_WAIT, Material.MUSIC_DISC_OTHERSIDE, Material.MUSIC_DISC_5, Material.MUSIC_DISC_PIGSTEP, Material.MUSIC_DISC_RELIC));

    public static Material getRandomDisc() {
        return ArrayUtils.getRandom(DISCS);
    }

    // Runs work on a server tick thread. This is the context-free fallback (it routes to the
    // global region scheduler on Folia); callers whose work touches a specific block or entity
    // must instead use Scheduler.region / Scheduler.entity so the work lands on the owning region.
    public static void runSyncTask(Runnable runnable) {
        if (MusicBox.getInstance() != null && MusicBox.getInstance().isEnabled()) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(runnable);
        }
    }

    public static <T> T extractMetadata(Class<T> metaType, Metadatable metadatable, String key) {
        List<MetadataValue> meta = metadatable.getMetadata(key);
        for (MetadataValue value : meta) {
            Object valueObj = value.value();
            try {
                return metaType.cast(valueObj);
            }
            catch (ClassCastException ignored) {
                // Wrong type; keep looking at the remaining metadata values.
            }
        }
        return null;
    }

    public static Location centerBlock(Location location) {
        return new Location(location.getWorld(), (double)location.getBlockX() + 0.5, (double)location.getBlockY() + 0.5, (double)location.getBlockZ() + 0.5);
    }

    public static Set<Player> findOpenPlayers(InventoryHolder holder) {
        BukkitUtils.checkPrimary();
        return Bukkit.getOnlinePlayers().stream().filter(p -> {
            Inventory inv = p.getOpenInventory().getTopInventory();
            @Nullable InventoryHolder cHolder = inv.getHolder();
            return holder.equals(cHolder);
        }).collect(Collectors.toSet());
    }

    public static void checkPrimary() {
        if (!Bukkit.isPrimaryThread()) {
            throw new RuntimeException("Call this only in primary thread");
        }
    }

    public static String locationToString(Location location) {
        return String.format("%s|%s|%s|%s", location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public static Location parseLocation(String string) {
        String[] split = string.split("\\|");
        if (split.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(split[0]);
        if (world == null) {
            return null;
        }
        double[] array = new double[3];
        for (int i = 0; i < 3; ++i) {
            try {
                array[i] = Double.parseDouble(split[i + 1]);
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return new Location(world, array[0], array[1], array[2]);
    }

    public static boolean inChunk(Location location, World chunkWorld, int chunkX, int chunkZ) {
        if (!Objects.requireNonNull(location.getWorld()).equals(chunkWorld)) {
            return false;
        }
        int xp = chunkX * 16;
        int zp = chunkZ * 16;
        int x = location.getBlockX();
        int z = location.getBlockZ();
        return xp <= x && xp + 15 >= x && zp <= z && zp + 15 >= z;
    }

    private BukkitUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}

