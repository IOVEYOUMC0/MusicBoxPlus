package ru.spliterash.musicbox.utils;

import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import ru.spliterash.musicbox.events.SourcedBlockRedstoneEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Честно спи****о у CraftBook'а
 */
@SuppressWarnings({"unused"})
@UtilityClass
public class RedstoneUtils {
    private final Set<Material> isRedstoneBlock = new HashSet<>();

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


    /**
     * Returns true if a block uses Redstone in some way.
     *
     * @param id the type ID of the block
     * @return true if the block uses Redstone
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isRedstoneBlock(Material id) {
        return isRedstoneBlock.contains(id);
    }

    @SuppressWarnings("DuplicatedCode")
    public void handleRedstoneForBlock(Block block, int oldLevel, int newLevel) {

        World world = block.getWorld();

        // Give the method a BlockWorldVector instead of a Block
        boolean wasOn = oldLevel >= 1;
        boolean isOn = newLevel >= 1;
        boolean wasChange = wasOn != isOn;

        // For efficiency reasons, we're only going to consider changes between
        // off and on state, and ignore simple current changes (i.e. 15->13)
        if (!wasChange) return;

        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // When this hook has been called, the level in the world has not
        // yet been updated, so we're going to do this very ugly thing of
        // faking the value with the new one whenever the data value of this
        // block is requested -- it is quite ugly
        switch (Material.matchMaterial(String.valueOf(block.getType()))) {
            case REDSTONE_WIRE:
                Material above = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y + 1, z).getType()));

                Material westSide = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y, z + 1).getType()));
                Material westSideAbove = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y + 1, z + 1).getType()));
                Material westSideBelow = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y - 1, z + 1).getType()));
                Material eastSide = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y, z - 1).getType()));
                Material eastSideAbove = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y + 1, z - 1).getType()));
                Material eastSideBelow = Material.matchMaterial(String.valueOf(world.getBlockAt(x, y - 1, z - 1).getType()));

                Material northSide = Material.matchMaterial(String.valueOf(world.getBlockAt(x - 1, y, z).getType()));
                Material northSideAbove = Material.matchMaterial(String.valueOf(world.getBlockAt(x - 1, y + 1, z).getType()));
                Material northSideBelow = Material.matchMaterial(String.valueOf(world.getBlockAt(x - 1, y - 1, z).getType()));
                Material southSide = Material.matchMaterial(String.valueOf(world.getBlockAt(x + 1, y, z).getType()));
                Material southSideAbove = Material.matchMaterial(String.valueOf(world.getBlockAt(x + 1, y + 1, z).getType()));
                Material southSideBelow = Material.matchMaterial(String.valueOf(world.getBlockAt(x + 1, y - 1, z).getType()));

                // Make sure that the wire points to only this block
                if (!isRedstoneBlock(westSide) && !isRedstoneBlock(eastSide)
                        && (!isRedstoneBlock(westSideAbove) || westSide == Material.AIR || above != Material.AIR)
                        && (!isRedstoneBlock(eastSideAbove) || eastSide == Material.AIR || above != Material.AIR)
                        && (!isRedstoneBlock(westSideBelow) || westSide != Material.AIR)
                        && (!isRedstoneBlock(eastSideBelow) || eastSide != Material.AIR)) {
                    // Possible blocks north / south
                    handleDirectWireInput(x - 1, y, z, block, oldLevel, newLevel);
                    handleDirectWireInput(x + 1, y, z, block, oldLevel, newLevel);
                    handleDirectWireInput(x - 1, y - 1, z, block, oldLevel, newLevel);
                    handleDirectWireInput(x + 1, y - 1, z, block, oldLevel, newLevel);
                }

                if (!isRedstoneBlock(northSide) && !isRedstoneBlock(southSide)
                        && (!isRedstoneBlock(northSideAbove) || northSide == Material.AIR || above != Material.AIR)
                        && (!isRedstoneBlock(southSideAbove) || southSide == Material.AIR || above != Material.AIR)
                        && (!isRedstoneBlock(northSideBelow) || northSide != Material.AIR)
                        && (!isRedstoneBlock(southSideBelow) || southSide != Material.AIR)) {
                    // Possible blocks west / east
                    handleDirectWireInput(x, y, z - 1, block, oldLevel, newLevel);
                    handleDirectWireInput(x, y, z + 1, block, oldLevel, newLevel);
                    handleDirectWireInput(x, y - 1, z - 1, block, oldLevel, newLevel);
                    handleDirectWireInput(x, y - 1, z + 1, block, oldLevel, newLevel);
                }

                // Can be triggered from below
                handleDirectWireInput(x, y + 1, z, block, oldLevel, newLevel);

                // Can be triggered from above
                handleDirectWireInput(x, y - 1, z, block, oldLevel, newLevel);
                return;
            case COMPARATOR:
                BlockFace f = VersionUtils.getRotation(block);
                handleDirectWireInput(x + f.getModX(), y, z + f.getModZ(), block, oldLevel, newLevel);
                if (Material.matchMaterial(String.valueOf(block.getRelative(f).getType())) != Material.AIR) {
                    handleDirectWireInput(x + f.getModX(), y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    handleDirectWireInput(x + f.getModX(), y + 1, z + f.getModZ(), block, oldLevel, newLevel);
                    handleDirectWireInput(x + f.getModX() + 1, y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    handleDirectWireInput(x + f.getModX() - 1, y - 1, z + f.getModZ(), block, oldLevel, newLevel);
                    handleDirectWireInput(x + f.getModX() + 1, y - 1, z + f.getModZ() + 1, block, oldLevel, newLevel);
                    handleDirectWireInput(x + f.getModX() - 1, y - 1, z + f.getModZ() - 1, block, oldLevel, newLevel);
                }
                return;
            case REPEATER, ACACIA_BUTTON, BIRCH_BUTTON, DARK_OAK_BUTTON, JUNGLE_BUTTON, OAK_BUTTON, SPRUCE_BUTTON, STONE_BUTTON, LEVER, REDSTONE_TORCH:
                BlockFace face = VersionUtils.getRotation(block);
                if (face != null) {
                    face = face.getOppositeFace();
                    handleDirectWireInput(x + face.getModX() * 2, y + face.getModY() * 2, z + face.getModZ() * 2, block, oldLevel, newLevel);
                }
                break;
            case POWERED_RAIL:
            case ACTIVATOR_RAIL:
                return;
        }

        // For redstone wires and repeaters, the code already exited this method
        // Non-wire blocks proceed

        handleDirectWireInput(x - 1, y, z, block, oldLevel, newLevel);
        handleDirectWireInput(x + 1, y, z, block, oldLevel, newLevel);
        handleDirectWireInput(x - 1, y - 1, z, block, oldLevel, newLevel);
        handleDirectWireInput(x + 1, y - 1, z, block, oldLevel, newLevel);
        handleDirectWireInput(x, y, z - 1, block, oldLevel, newLevel);
        handleDirectWireInput(x, y, z + 1, block, oldLevel, newLevel);
        handleDirectWireInput(x, y - 1, z - 1, block, oldLevel, newLevel);
        handleDirectWireInput(x, y - 1, z + 1, block, oldLevel, newLevel);

        // Can be triggered from below
        handleDirectWireInput(x, y + 1, z, block, oldLevel, newLevel);

        // Can be triggered from above
        handleDirectWireInput(x, y - 1, z, block, oldLevel, newLevel);
    }

    private void handleDirectWireInput(int x, int y, int z, Block sourceBlock, int oldLevel, int newLevel) {

        Block block = sourceBlock.getWorld().getBlockAt(x, y, z);
        if (sameBlock(sourceBlock.getLocation(), block.getLocation())) //The same block, don't run.
            return;
        final SourcedBlockRedstoneEvent event = new SourcedBlockRedstoneEvent(sourceBlock, block, oldLevel, newLevel);

        Bukkit.getPluginManager().callEvent(event);

    }

    public final double EQUALS_PRECISION = 0.0001;

    public boolean sameBlock(org.bukkit.Location a, org.bukkit.Location b) {

        return Math.abs(a.getX() - b.getX()) <= EQUALS_PRECISION && Math.abs(a.getY() - b.getY()) <= EQUALS_PRECISION
                && Math.abs(a.getZ() - b.getZ()) <= EQUALS_PRECISION;
    }

    public int getPin(Block sign, Block source) {
        BlockFace signFace = VersionUtils.getRotation(sign);
        // Если каким то образом табличка стоит неправильно или сигнал идёт сверху
        if (!FaceUtils.isValidFace(signFace))
            return 0;
        BlockFace sourceFace = FaceUtils.getRelativeFace(sign.getLocation(), source.getLocation());
        if (sourceFace.equals(BlockFace.SELF))
            return 0;
        return FaceUtils.getPin(signFace, sourceFace);
    }

}
