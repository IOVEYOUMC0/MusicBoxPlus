package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.api.event.SourcedBlockRedstoneEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.HashSet;
import java.util.Set;

public final class RedstoneUtils {
    private static final Set<Material> isRedstoneBlock = new HashSet<Material>();

    // Materials that pass the redstone fast pre-check, precomputed to avoid a per-event String alloc.
    private static final Set<Material> FAST_PRECHECK_MATERIALS = new HashSet<>();
    // Blocks the SourcedBlockRedstoneEvent listener actually acts on (signs + jukebox).
    private static final Set<Material> REDSTONE_CALLBACK_TARGETS = new HashSet<>();
    static {
        FAST_PRECHECK_MATERIALS.add(Material.REDSTONE_WIRE);
        FAST_PRECHECK_MATERIALS.add(Material.COMPARATOR);
        FAST_PRECHECK_MATERIALS.add(Material.REPEATER);
        FAST_PRECHECK_MATERIALS.add(Material.LEVER);
        FAST_PRECHECK_MATERIALS.add(Material.REDSTONE_TORCH);
        // POWERED_RAIL / ACTIVATOR_RAIL are deliberately absent: rails never power a sign or
        // jukebox, so their frequent redstone events would only reach a no-op switch case. That
        // case is kept as a guard for direct handleRedstoneForBlock calls.
        FAST_PRECHECK_MATERIALS.addAll(Tag.BUTTONS.getValues());

        REDSTONE_CALLBACK_TARGETS.add(Material.JUKEBOX);
        for (Material m : Material.values()) {
            if (m.name().endsWith("SIGN")) {
                REDSTONE_CALLBACK_TARGETS.add(m);
            }
        }
    }

    public static boolean isRedstoneBlock(Material id) {
        return isRedstoneBlock.contains(id);
    }

    public static boolean isFastPreCheckPass(Block block) {
        return FAST_PRECHECK_MATERIALS.contains(block.getType());
    }

    public static void handleRedstoneForBlock(Block block, int oldLevel, int newLevel) {
        World world = block.getWorld();
        boolean wasOn = oldLevel >= 1;
        boolean isOn = newLevel >= 1;
        boolean wasChange = wasOn != isOn;
        if (!wasChange) {
            return;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        Material blockType = block.getType();
        switch (blockType) {
            case REDSTONE_WIRE: {
                Material above = world.getBlockAt(x, y + 1, z).getType();
                Material westSide = world.getBlockAt(x, y, z + 1).getType();
                Material westSideAbove = world.getBlockAt(x, y + 1, z + 1).getType();
                Material westSideBelow = world.getBlockAt(x, y - 1, z + 1).getType();
                Material eastSide = world.getBlockAt(x, y, z - 1).getType();
                Material eastSideAbove = world.getBlockAt(x, y + 1, z - 1).getType();
                Material eastSideBelow = world.getBlockAt(x, y - 1, z - 1).getType();
                Material northSide = world.getBlockAt(x - 1, y, z).getType();
                Material northSideAbove = world.getBlockAt(x - 1, y + 1, z).getType();
                Material northSideBelow = world.getBlockAt(x - 1, y - 1, z).getType();
                Material southSide = world.getBlockAt(x + 1, y, z).getType();
                Material southSideAbove = world.getBlockAt(x + 1, y + 1, z).getType();
                Material southSideBelow = world.getBlockAt(x + 1, y - 1, z).getType();
                if (!(RedstoneUtils.isRedstoneBlock(westSide) || RedstoneUtils.isRedstoneBlock(eastSide) || RedstoneUtils.isRedstoneBlock(westSideAbove) && westSide != Material.AIR && above == Material.AIR || RedstoneUtils.isRedstoneBlock(eastSideAbove) && eastSide != Material.AIR && above == Material.AIR || RedstoneUtils.isRedstoneBlock(westSideBelow) && westSide == Material.AIR || RedstoneUtils.isRedstoneBlock(eastSideBelow) && eastSide == Material.AIR)) {
                    RedstoneUtils.handleDirectWireInput(x - 1, y, z, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + 1, y, z, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x - 1, y - 1, z, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + 1, y - 1, z, block, oldLevel, newLevel);
                }
                if (!(RedstoneUtils.isRedstoneBlock(northSide) || RedstoneUtils.isRedstoneBlock(southSide) || RedstoneUtils.isRedstoneBlock(northSideAbove) && northSide != Material.AIR && above == Material.AIR || RedstoneUtils.isRedstoneBlock(southSideAbove) && southSide != Material.AIR && above == Material.AIR || RedstoneUtils.isRedstoneBlock(northSideBelow) && northSide == Material.AIR || RedstoneUtils.isRedstoneBlock(southSideBelow) && southSide == Material.AIR)) {
                    RedstoneUtils.handleDirectWireInput(x, y, z - 1, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x, y, z + 1, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x, y - 1, z - 1, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x, y - 1, z + 1, block, oldLevel, newLevel);
                }
                RedstoneUtils.handleDirectWireInput(x, y + 1, z, block, oldLevel, newLevel);
                RedstoneUtils.handleDirectWireInput(x, y - 1, z, block, oldLevel, newLevel);
                return;
            }
            case COMPARATOR: {
                // A comparator's Directional.getFacing() points at the block it READS (its back/input
                // side; in vanilla FACING is the input, output = getOpposite()). Dispatch must follow
                // the OUTPUT (front) side -- the block it actually powers -- hence the flip, same as
                // the REPEATER branch below. Without it, a comparator merely reading a jukebox feeds
                // its own output rise back into that jukebox, which JukeboxPlayer.onRedstone takes as
                // a play/advance command and ejects or duplicates the disc on every 0->N activation.
                BlockFace f = VersionUtils.getRotation(block).getOppositeFace();
                RedstoneUtils.handleDirectWireInput(x + f.getModX(), y, z + f.getModZ(), block, oldLevel, newLevel);
                if (block.getRelative(f).getType() != Material.AIR) {
                    RedstoneUtils.handleDirectWireInput(x + f.getModX(), y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + f.getModX(), y + 1, z + f.getModZ(), block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + f.getModX() + 1, y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + f.getModX() - 1, y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + f.getModX() + 1, y - 1, z + f.getModZ() + 1, block, oldLevel, newLevel);
                    RedstoneUtils.handleDirectWireInput(x + f.getModX() - 1, y - 1, z + f.getModZ() - 1, block, oldLevel, newLevel);
                }
                return;
            }
            case REPEATER: 
            case ACACIA_BUTTON: 
            case BIRCH_BUTTON: 
            case DARK_OAK_BUTTON: 
            case JUNGLE_BUTTON: 
            case OAK_BUTTON: 
            case SPRUCE_BUTTON: 
            case STONE_BUTTON: 
            case LEVER: 
            case REDSTONE_TORCH: {
                BlockFace face = VersionUtils.getRotation(block);
                if (face == null) break;
                face = face.getOppositeFace();
                RedstoneUtils.handleDirectWireInput(x + face.getModX() * 2, y + face.getModY() * 2, z + face.getModZ() * 2, block, oldLevel, newLevel);
                break;
            }
            case POWERED_RAIL: 
            case ACTIVATOR_RAIL: {
                return;
            }
            default:
                break;
        }
        RedstoneUtils.handleDirectWireInput(x - 1, y, z, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x + 1, y, z, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x - 1, y - 1, z, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x + 1, y - 1, z, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y, z - 1, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y, z + 1, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y - 1, z - 1, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y - 1, z + 1, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y + 1, z, block, oldLevel, newLevel);
        RedstoneUtils.handleDirectWireInput(x, y - 1, z, block, oldLevel, newLevel);
    }

    private static void handleDirectWireInput(int x, int y, int z, Block sourceBlock, int oldLevel, int newLevel) {
        // Skip the source's own position with a primitive int compare: comparing Locations here
        // would allocate two Locations (plus a Block from getBlockAt) per probe just to compare
        // coordinates already available as ints, at ~10 probes per redstone edge.
        if (x == sourceBlock.getX() && y == sourceBlock.getY() && z == sourceBlock.getZ()) {
            return;
        }
        Block block = sourceBlock.getWorld().getBlockAt(x, y, z);
        // The sole listener (onRedstoneCB) only acts on signs and jukeboxes. A cheap getType()
        // check skips the event allocation, dispatch and BlockState snapshot for the wire/air
        // neighbours, which are the bulk of the ~10 probes per redstone change.
        if (!REDSTONE_CALLBACK_TARGETS.contains(block.getType())) {
            return;
        }
        SourcedBlockRedstoneEvent event = new SourcedBlockRedstoneEvent(sourceBlock, block, oldLevel, newLevel);
        Bukkit.getPluginManager().callEvent(event);
    }


    public static int getPin(Block sign, Block source) {
        BlockFace signFace = VersionUtils.getRotation(sign);
        if (!FaceUtils.isValidFace(signFace)) {
            signFace = FaceUtils.normalizeFace(signFace);
        }
        BlockFace sourceFace = FaceUtils.getRelativeFace(sign.getLocation(), source.getLocation());
        if (sourceFace.equals(BlockFace.SELF)) {
            return 0;
        }
        return FaceUtils.getPin(signFace, sourceFace);
    }

    private RedstoneUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static {
        isRedstoneBlock.add(Material.POWERED_RAIL);
        isRedstoneBlock.add(Material.DETECTOR_RAIL);
        isRedstoneBlock.add(Material.STICKY_PISTON);
        isRedstoneBlock.add(Material.PISTON);
        isRedstoneBlock.add(Material.LEVER);
        isRedstoneBlock.add(Material.STONE_PRESSURE_PLATE);
        isRedstoneBlock.addAll(ItemUtils.getEndWith("_PRESSURE_PLATE"));
        isRedstoneBlock.add(Material.REDSTONE_TORCH);
        isRedstoneBlock.add(Material.REDSTONE_WALL_TORCH);
        isRedstoneBlock.add(Material.REDSTONE_WIRE);
        isRedstoneBlock.addAll(ItemUtils.getEndWith("DOOR"));
        isRedstoneBlock.add(Material.TNT);
        isRedstoneBlock.add(Material.DISPENSER);
        isRedstoneBlock.add(Material.NOTE_BLOCK);
        isRedstoneBlock.add(Material.REPEATER);
        isRedstoneBlock.add(Material.TRIPWIRE_HOOK);
        isRedstoneBlock.add(Material.COMMAND_BLOCK);
        isRedstoneBlock.addAll(ItemUtils.getEndWith("_BUTTON"));
        isRedstoneBlock.add(Material.TRAPPED_CHEST);
        isRedstoneBlock.add(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
        isRedstoneBlock.add(Material.LIGHT_WEIGHTED_PRESSURE_PLATE);
        isRedstoneBlock.add(Material.COMPARATOR);
        isRedstoneBlock.add(Material.REDSTONE_BLOCK);
        isRedstoneBlock.add(Material.HOPPER);
        isRedstoneBlock.add(Material.ACTIVATOR_RAIL);
        isRedstoneBlock.add(Material.DROPPER);
        isRedstoneBlock.add(Material.DAYLIGHT_DETECTOR);
    }
}

