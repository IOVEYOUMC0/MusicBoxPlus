package ru.spliterash.musicbox.utils;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rotatable;

public class VersionUtils {

    public static BlockFace getRotation(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Rotatable) {
            return ((Rotatable) data).getRotation();
        } else if (data instanceof Directional) {
            return ((Directional) data).getFacing();
        }
        return BlockFace.SELF;
    }

    public static void setRotation(Block block, BlockFace face) {
        BlockData data = block.getBlockData();
        if (data instanceof Rotatable) {
            ((Rotatable) data).setRotation(face);
        } else if (data instanceof Directional) {
            ((Directional) data).setFacing(face);
        }
        block.setBlockData(data, false);
    }

    public static void setLever(Block block, boolean powered) {
        BlockData data = block.getBlockData();
        if (data instanceof Powerable) {
            ((Powerable) data).setPowered(powered);
            block.setBlockData(data, true);
        }
    }
}