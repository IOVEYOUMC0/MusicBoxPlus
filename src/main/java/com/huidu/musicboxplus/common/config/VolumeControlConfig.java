package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;

public class VolumeControlConfig {
    String title = "<gold>Volume Control";
    String levelFormat = "<yellow>Volume: {volume}%";
    String increaseText = "<green>Increase Volume";
    String decreaseText = "<red>Decrease Volume";
    Material materialMute = Material.BARRIER;
    Material materialLow = Material.RED_WOOL;
    Material materialMedium = Material.ORANGE_WOOL;
    Material materialHigh = Material.YELLOW_WOOL;
    Material materialFull = Material.GREEN_WOOL;
    int customModelDataMute = 0;
    int customModelDataLow = 0;
    int customModelDataMedium = 0;
    int customModelDataHigh = 0;
    int customModelDataFull = 0;

    public String getTitle() {
        return this.title;
    }

    public String getLevelFormat() {
        return this.levelFormat;
    }

    public String getIncreaseText() {
        return this.increaseText;
    }

    public String getDecreaseText() {
        return this.decreaseText;
    }

    public Material getMaterialMute() {
        return this.materialMute;
    }

    public Material getMaterialLow() {
        return this.materialLow;
    }

    public Material getMaterialMedium() {
        return this.materialMedium;
    }

    public Material getMaterialHigh() {
        return this.materialHigh;
    }

    public Material getMaterialFull() {
        return this.materialFull;
    }

    public int getCustomModelDataMute() {
        return this.customModelDataMute;
    }

    public int getCustomModelDataLow() {
        return this.customModelDataLow;
    }

    public int getCustomModelDataMedium() {
        return this.customModelDataMedium;
    }

    public int getCustomModelDataHigh() {
        return this.customModelDataHigh;
    }

    public int getCustomModelDataFull() {
        return this.customModelDataFull;
    }
}
