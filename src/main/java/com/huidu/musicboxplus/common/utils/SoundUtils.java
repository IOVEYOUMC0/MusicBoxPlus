package com.huidu.musicboxplus.common.utils;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class SoundUtils {
    
    private static final float DEFAULT_CLICK_VOLUME = 0.5f;
    private static final float DEFAULT_CLICK_PITCH = 1.0f;
    
    private SoundUtils() {
    }
    
    public static void playClickSound(Player player) {
        if (player == null) {
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, DEFAULT_CLICK_VOLUME, DEFAULT_CLICK_PITCH);
    }
    
    public static void playSound(Player player, Sound sound) {
        if (player == null || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, DEFAULT_CLICK_VOLUME, DEFAULT_CLICK_PITCH);
    }
    
    public static void playSound(Player player, Sound sound, float volume, float pitch) {
        if (player == null || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
    
    public static void playSoundAt(Location location, Sound sound, float volume, float pitch) {
        if (location == null || location.getWorld() == null || sound == null) {
            return;
        }
        location.getWorld().playSound(location, sound, volume, pitch);
    }
}
