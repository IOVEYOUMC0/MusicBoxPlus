package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class SongItemConfig {
    boolean customEnabled = false;
    Material customMaterial = Material.MUSIC_DISC_CAT;
    int customModelData = 0;
    String itemModel = "";
    boolean useRandomDisc = true;
    List<Material> availableDiscs = new ArrayList<Material>();
    String nameFormat = "<yellow>{song}";
    List<String> loreFormat = new ArrayList<String>();
    String aliasesFormat = "<gray>Aliases: <yellow>{aliases}";
    String tagsFormat = "<gray>Tags: <aqua>{tags}";

    public boolean isCustomEnabled() {
        return this.customEnabled;
    }

    public Material getCustomMaterial() {
        return this.customMaterial;
    }

    public int getCustomModelData() {
        return this.customModelData;
    }

    public String getItemModel() {
        return this.itemModel;
    }

    public boolean isUseRandomDisc() {
        return this.useRandomDisc;
    }

    public List<Material> getAvailableDiscs() {
        return this.availableDiscs;
    }

    public String getNameFormat() {
        return this.nameFormat;
    }

    public List<String> getLoreFormat() {
        return this.loreFormat;
    }

    public String getAliasesFormat() {
        return this.aliasesFormat;
    }

    public String getTagsFormat() {
        return this.tagsFormat;
    }
}
