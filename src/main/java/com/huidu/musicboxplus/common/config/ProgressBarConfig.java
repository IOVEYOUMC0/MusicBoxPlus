package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class ProgressBarConfig {
    boolean enabled = true;
    int row = 0;
    int startCol = 0;
    int endCol = 9;
    Material materialUnplayed = Material.GRAY_STAINED_GLASS_PANE;
    int materialUnplayedModelData = 0;
    Material materialPlaying = Material.WHITE_STAINED_GLASS_PANE;
    int materialPlayingModelData = 0;
    Material materialHalfway = Material.LIME_STAINED_GLASS_PANE;
    int materialHalfwayModelData = 0;
    Material materialPlayed = Material.GREEN_STAINED_GLASS_PANE;
    int materialPlayedModelData = 0;
    String format = "<yellow>{percent}% <gray>- {time}";
    List<String> lore = new ArrayList<String>();

    public boolean isEnabled() {
        return this.enabled;
    }

    public int getRow() {
        return this.row;
    }

    public int getStartCol() {
        return this.startCol;
    }

    public int getEndCol() {
        return this.endCol;
    }

    public Material getMaterialUnplayed() {
        return this.materialUnplayed;
    }

    public int getMaterialUnplayedModelData() {
        return this.materialUnplayedModelData;
    }

    public Material getMaterialPlaying() {
        return this.materialPlaying;
    }

    public int getMaterialPlayingModelData() {
        return this.materialPlayingModelData;
    }

    public Material getMaterialHalfway() {
        return this.materialHalfway;
    }

    public int getMaterialHalfwayModelData() {
        return this.materialHalfwayModelData;
    }

    public Material getMaterialPlayed() {
        return this.materialPlayed;
    }

    public int getMaterialPlayedModelData() {
        return this.materialPlayedModelData;
    }

    public String getFormat() {
        return this.format;
    }

    public List<String> getLore() {
        return this.lore;
    }
}
