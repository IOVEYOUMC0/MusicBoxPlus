package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PlaylistItemConfig {
    Material material = Material.PAPER;
    int customModelData;
    String itemModel = "";
    String craftEngineItem = "";
    String nameFormat = "<gold>{playlist}";
    List<String> loreFormat = new ArrayList<String>();
    List<String> itemLore = new ArrayList<String>();
    List<String> listLore = new ArrayList<String>();
    List<String> addSongLore = new ArrayList<String>();
    List<String> addSongExistsLore = new ArrayList<String>();
    List<String> addContainerLore = new ArrayList<String>();
    List<String> defaultLore = new ArrayList<String>();

    public PlaylistItemConfig() {
        itemLore.add("<gray>Left click to play</gray>");
        itemLore.add("<gray>Right click to remove</gray>");
        listLore.add("<gray>Left click to open</gray>");
        listLore.add("<gray>Right click to edit</gray>");
        addSongLore.add("<yellow>Click to add</yellow>");
        addSongExistsLore.add("<gray>Already in playlist</gray>");
        addContainerLore.add("<yellow>Right-click to add all</yellow>");
        defaultLore.add("<gray>Default playlist</gray>");
        defaultLore.add("<gray>Contains all available songs</gray>");
    }

    public String getNameFormat() {
        return this.nameFormat;
    }

    public Material getMaterial() { return this.material; }
    public int getCustomModelData() { return this.customModelData; }
    public String getItemModel() { return this.itemModel; }
    public String getCraftEngineItem() { return this.craftEngineItem; }

    public List<String> getLoreFormat() {
        return this.loreFormat;
    }

    public List<String> getItemLore() {
        return this.itemLore;
    }

    public List<String> getListLore() {
        return this.listLore;
    }

    public List<String> getAddSongLore() {
        return this.addSongLore;
    }

    public List<String> getAddSongExistsLore() {
        return this.addSongExistsLore;
    }

    public List<String> getAddContainerLore() {
        return this.addContainerLore;
    }

    public List<String> getDefaultLore() {
        return this.defaultLore;
    }
}
