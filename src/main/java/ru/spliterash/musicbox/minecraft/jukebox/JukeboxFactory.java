package ru.spliterash.musicbox.minecraft.jukebox;

import org.bukkit.block.Jukebox;

public class JukeboxFactory {

    public static ru.spliterash.musicbox.minecraft.jukebox.IJukebox getJukebox(Jukebox jukebox) {
        return new PaperJukebox(jukebox);
    }
}