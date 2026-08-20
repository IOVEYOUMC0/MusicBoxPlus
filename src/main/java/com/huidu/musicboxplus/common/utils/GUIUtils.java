package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import org.bukkit.Material;

public final class GUIUtils {
    private GUIUtils() {
    }

    public static Material getVolumeMaterial(int volume) {
        GUIConfigManager.VolumeControlConfig config = GUIConfigManager.getInstance().getVolumeControlConfig();
        if (volume == 0) {
            return config.getMaterialMute() != null ? config.getMaterialMute() : Material.BARRIER;
        }
        if (volume <= 25) {
            return config.getMaterialLow() != null ? config.getMaterialLow() : Material.RED_WOOL;
        }
        if (volume <= 50) {
            return config.getMaterialMedium() != null ? config.getMaterialMedium() : Material.ORANGE_WOOL;
        }
        if (volume <= 75) {
            return config.getMaterialHigh() != null ? config.getMaterialHigh() : Material.YELLOW_WOOL;
        }
        return config.getMaterialFull() != null ? config.getMaterialFull() : Material.GREEN_WOOL;
    }
}

