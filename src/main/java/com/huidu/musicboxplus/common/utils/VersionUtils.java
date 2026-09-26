package com.huidu.musicboxplus.common.utils;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rotatable;

// Small helpers around BlockData that read or write the orientation / power state
// of a block regardless of which concrete data type it uses.
public final class VersionUtils {

    private VersionUtils() {
    }

    public static BlockFace getRotation(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Rotatable rotatable) {
            return rotatable.getRotation();
        }
        if (data instanceof Directional directional) {
            return directional.getFacing();
        }
        return BlockFace.SELF;
    }

    public static void setRotation(Block block, BlockFace face) {
        BlockData data = block.getBlockData();
        if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(face);
        } else if (data instanceof Directional directional) {
            directional.setFacing(face);
        }
        block.setBlockData(data, false);
    }

    public static void setLever(Block block, boolean powered) {
        BlockData data = block.getBlockData();
        if (data instanceof Powerable powerable) {
            powerable.setPowered(powered);
            block.setBlockData(data, true);
        }
    }
}