package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.metadata.Metadatable;

import java.util.*;

public final class BukkitUtils {

    // Discovered from Material instead of listed by hand: the hand-written list stopped at the discs
    // that existed when it was written, so a disc added by a later server version could never be
    // handed out as a random reward even though the server supports it. Any Material whose name
    // starts with MUSIC_DISC_ is a real, playable disc in whatever version is running. Built lazily
    // (Material.values() clones its array) and cached: the set cannot change while the server runs.
    private static volatile List<Material> discs;

    /** Every playable music disc the running server version knows about, sorted by name. */
    public static List<Material> discs() {
        List<Material> cached = discs;
        if (cached != null) {
            return cached;
        }
        synchronized (BukkitUtils.class) {
            cached = discs;
            if (cached == null) {
                List<Material> found = new ArrayList<>();
                for (Material material : Material.values()) {
                    if (material.name().startsWith("MUSIC_DISC_") && material.isItem()) {
                        found.add(material);
                    }
                }
                found.sort(Comparator.comparing(Material::name));
                cached = List.copyOf(found);
                discs = cached;
            }
        }
        return cached;
    }

    public static Material getRandomDisc() {
        return ArrayUtils.getRandom(discs());
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
