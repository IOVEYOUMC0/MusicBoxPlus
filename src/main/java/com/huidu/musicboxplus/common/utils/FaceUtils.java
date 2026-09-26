package com.huidu.musicboxplus.common.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class FaceUtils {
    private static final Set<BlockFace> validSignFace = new HashSet<BlockFace>();
    private static final Set<BlockFace> searchFace = new HashSet<BlockFace>();
    // Materials that can hold an inventory, so a non-container neighbour can be rejected
    // without allocating a BlockState snapshot for it.
    private static final Set<Material> INVENTORY_HOLDER_MATERIALS = new HashSet<>();
    // Jukebox materials, used by getRelativeAround as a per-target-type prefilter.
    private static final Set<Material> JUKEBOX_MATERIALS = Set.of(Material.JUKEBOX);
    // Chest materials (trapped chests included). ENDER_CHEST's BlockState is not a Chest,
    // so the instanceof check is what finally rules it out.
    private static final Set<Material> CHEST_MATERIALS = new HashSet<>();
    // Per-type prefilter table for getRelativeAround. Only the BlockState types this project
    // actually queries are registered; an unregistered type simply skips the prefilter, so
    // correctness never depends on this optimisation being complete.
    private static final Map<Class<?>, Set<Material>> PREFILTER_BY_STATE_TYPE = new HashMap<>();

    public static BlockFace getClockWise(BlockFace face) {
        switch (face) {
            case NORTH: {
                return BlockFace.EAST;
            }
            case EAST: {
                return BlockFace.SOUTH;
            }
            case SOUTH: {
                return BlockFace.WEST;
            }
            case WEST: {
                return BlockFace.NORTH;
            }
            default:
                return BlockFace.SELF;
        }
    }

    public static BlockFace getCounterClockWise(BlockFace face) {
        switch (face) {
            case NORTH: {
                return BlockFace.WEST;
            }
            case EAST: {
                return BlockFace.NORTH;
            }
            case SOUTH: {
                return BlockFace.EAST;
            }
            case WEST: {
                return BlockFace.SOUTH;
            }
            default:
                return BlockFace.SELF;
        }
    }

    public static int getPin(BlockFace from, BlockFace to) {
        BlockFace normalizedFrom = normalizeDiagonalFace(from);
        BlockFace normalizedTo = normalizeDiagonalFace(to);
        
        if (normalizedFrom == normalizedTo) {
            return 0;
        }
        if (FaceUtils.getCounterClockWise(normalizedTo) == normalizedFrom) {
            return 1;
        }
        if (FaceUtils.getClockWise(normalizedTo) == normalizedFrom) {
            return 2;
        }
        if (FaceUtils.invertFace(normalizedTo) == normalizedFrom) {
            return 3;
        }
        return 0;
    }
    
    public static BlockFace normalizeDiagonalFace(BlockFace face) {
        switch (face) {
            case NORTH_EAST:
            case NORTH_WEST:
            case SOUTH_EAST:
            case SOUTH_WEST:
                return getClosestCardinal(face);
            default:
                return face;
        }
    }
    
    private static BlockFace getClosestCardinal(BlockFace diagonal) {
        int modX = diagonal.getModX();
        int modZ = diagonal.getModZ();
        
        if (Math.abs(modX) >= Math.abs(modZ)) {
            return modX > 0 ? BlockFace.EAST : BlockFace.WEST;
        } else {
            return modZ > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }
    }

    public static boolean isValidFace(BlockFace face) {
        return validSignFace.contains(face);
    }

    public static BlockFace getRelativeFace(Location from, Location to) {
        // 直接用坐标整数相减，避免无意义的 Vector 分配
        int dx = from.getBlockX() - to.getBlockX();
        int dz = from.getBlockZ() - to.getBlockZ();
        
        if (dx > 0 && dz == 0) {
            return BlockFace.WEST;
        }
        if (dx < 0 && dz == 0) {
            return BlockFace.EAST;
        }
        if (dx == 0 && dz > 0) {
            return BlockFace.NORTH;
        }
        if (dx == 0 && dz < 0) {
            return BlockFace.SOUTH;
        }
        if (dx > 0 && dz > 0) {
            return BlockFace.NORTH_WEST;
        }
        if (dx > 0 && dz < 0) {
            return BlockFace.SOUTH_WEST;
        }
        if (dx < 0 && dz > 0) {
            return BlockFace.NORTH_EAST;
        }
        if (dx < 0 && dz < 0) {
            return BlockFace.SOUTH_EAST;
        }
        return BlockFace.SELF;
    }

    public static BlockFace invertFace(BlockFace face) {
        switch (face) {
            case EAST: {
                return BlockFace.WEST;
            }
            case NORTH: {
                return BlockFace.SOUTH;
            }
            case WEST: {
                return BlockFace.EAST;
            }
            case SOUTH: {
                return BlockFace.NORTH;
            }
            default:
                return BlockFace.SELF;
        }
    }

    public static BlockFace normalizeFace(BlockFace face) {
        if (face.getModX() == 2) {
            return BlockFace.EAST;
        }
        if (face.getModX() == -2) {
            return BlockFace.WEST;
        }
        if (face.getModZ() == 2) {
            return BlockFace.SOUTH;
        }
        if (face.getModZ() == -2) {
            return BlockFace.NORTH;
        }
        for (BlockFace value : BlockFace.values()) {
            if (value.getModX() != face.getModX()) continue;
            return value;
        }
        return BlockFace.NORTH;
    }

    @Nullable
    public static <T extends BlockState> T getRelativeAround(Block block, Class<T> tClass) {
        // The prefilter must be keyed on the requested type, not on "is an InventoryHolder":
        // on Paper, Jukebox also implements BlockInventoryHolder, so reusing the container
        // material table would filter every JUKEBOX out and make this method always return null.
        // Unregistered types run without a prefilter.
        Set<Material> prefilter = PREFILTER_BY_STATE_TYPE.get(tClass);
        for (BlockFace face : searchFace) {
            Block anotherBlock = block.getRelative(face);
            // Cheap material check first, so no BlockState snapshot is allocated for a miss.
            if (prefilter != null && !prefilter.contains(anotherBlock.getType())) {
                continue;
            }
            BlockState state = anotherBlock.getState();
            if (!tClass.isInstance(state)) continue;
            return tClass.cast(state);
        }
        return null;
    }

    // Finds any adjacent inventory container (chest, barrel, shulker box, ...), null if there is none.
    @Nullable
    public static Inventory getAdjacentInventory(Block block) {
        for (BlockFace face : searchFace) {
            Block anotherBlock = block.getRelative(face);
            // Cheap material check first, so no BlockState snapshot is allocated for a miss.
            if (!INVENTORY_HOLDER_MATERIALS.contains(anotherBlock.getType())) {
                continue;
            }
            BlockState state = anotherBlock.getState();
            if (state instanceof InventoryHolder holder) {
                return holder.getInventory();
            }
        }
        return null;
    }

    private FaceUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static {
        validSignFace.add(BlockFace.EAST);
        validSignFace.add(BlockFace.NORTH);
        validSignFace.add(BlockFace.SOUTH);
        validSignFace.add(BlockFace.WEST);
        searchFace.addAll(validSignFace);
        searchFace.add(BlockFace.UP);
        searchFace.add(BlockFace.DOWN);
        // JUKEBOX is deliberately left out: this table only serves getAdjacentInventory, which
        // looks for a playlist container, and listing it would make a neighbouring jukebox be
        // mistaken for a playlist source.
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue;
            String name = m.name();
            if (name.endsWith("_CHEST") || name.equals("CHEST") || name.equals("BARREL")
                || name.endsWith("SHULKER_BOX") || name.equals("HOPPER")) {
                INVENTORY_HOLDER_MATERIALS.add(m);
            }
            if (name.endsWith("_CHEST") || name.equals("CHEST")) {
                CHEST_MATERIALS.add(m);
            }
        }
        PREFILTER_BY_STATE_TYPE.put(org.bukkit.block.Jukebox.class, JUKEBOX_MATERIALS);
        PREFILTER_BY_STATE_TYPE.put(org.bukkit.block.Chest.class, Set.copyOf(CHEST_MATERIALS));
    }
}
