package com.huidu.musicboxplus.module.jukebox.minecraft;

import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;

public class JukeboxFactory {

    public static IJukebox getJukebox(Block block) {
        return new PaperJukebox((Jukebox) block.getState());
    }

    public static IJukebox getJukebox(Jukebox jukebox) {
        return new PaperJukebox(jukebox);
    }

    public static boolean isJukeboxBlock(Block block) {
        return block.getState() instanceof Jukebox;
    }
}