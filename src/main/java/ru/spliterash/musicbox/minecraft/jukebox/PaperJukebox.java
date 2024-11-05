package ru.spliterash.musicbox.minecraft.jukebox;

import org.bukkit.Material;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import ru.spliterash.musicbox.MusicBox;
import org.bukkit.SoundCategory;

public class PaperJukebox implements IJukebox {
    private final Jukebox jukebox;

    public PaperJukebox(Jukebox jukebox) {
        this.jukebox = jukebox;
    }

    @Override
    public boolean isEmpty() {
        ItemStack item = jukebox.getRecord();
        return item == null || item.getType() == Material.AIR;
    }

    @Override
    public void setJukebox(ItemStack item) {
        jukebox.setRecord(item);
        jukebox.update();
        stopVanillaMusic(); // Try to stop sounds early.

        // If that fails, try again.
        new BukkitRunnable() {
            @Override
            public void run() {
                stopVanillaMusic();
            }
        }.runTaskLater(MusicBox.getInstance(), 1L);

        // And again.
        new BukkitRunnable() {
            @Override
            public void run() {
                stopVanillaMusic();
            }
        }.runTaskLater(MusicBox.getInstance(), 5L);

        // ...And again.
        new BukkitRunnable() {
            @Override
            public void run() {
                stopVanillaMusic();
            }
        }.runTaskLater(MusicBox.getInstance(), 20L);
        // There's likely a better way to do it, but hey, it works.
    }

    @Override
    public ItemStack getJukebox() {
        return jukebox.getRecord();
    }

    private void stopVanillaMusic() {
        if (jukebox.isPlaying()) {
            // Stop any lingering sounds using the stopsound command for all music discs.
            for (Player player : jukebox.getWorld().getPlayers()) {
                player.stopSound("music_disc.11", SoundCategory.RECORDS);
                player.stopSound("music_disc.13", SoundCategory.RECORDS);
                player.stopSound("music_disc.5", SoundCategory.RECORDS);
                player.stopSound("music_disc.blocks", SoundCategory.RECORDS);
                player.stopSound("music_disc.cat", SoundCategory.RECORDS);
                player.stopSound("music_disc.chirp", SoundCategory.RECORDS);
                player.stopSound("music_disc.creator", SoundCategory.RECORDS);
                player.stopSound("music_disc.creator_music_box", SoundCategory.RECORDS);
                player.stopSound("music_disc.far", SoundCategory.RECORDS);
                player.stopSound("music_disc.mall", SoundCategory.RECORDS);
                player.stopSound("music_disc.mellohi", SoundCategory.RECORDS);
                player.stopSound("music_disc.otherside", SoundCategory.RECORDS);
                player.stopSound("music_disc.pigstep", SoundCategory.RECORDS);
                player.stopSound("music_disc.precipice", SoundCategory.RECORDS);
                player.stopSound("music_disc.relic", SoundCategory.RECORDS);
                player.stopSound("music_disc.stal", SoundCategory.RECORDS);
                player.stopSound("music_disc.strad", SoundCategory.RECORDS);
                player.stopSound("music_disc.wait", SoundCategory.RECORDS);
                player.stopSound("music_disc.ward", SoundCategory.RECORDS);
                // Add any additional music discs here if needed.
            }
        }
    }
}