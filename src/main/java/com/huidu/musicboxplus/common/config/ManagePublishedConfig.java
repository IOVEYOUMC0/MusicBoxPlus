package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class ManagePublishedConfig {
    String title = "<gold>Published Music: {name}";
    String layout = "XXXIXXXXX\nXPXSXUXTX\nXXXDXBXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public ManagePublishedConfig() {
        buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("stats", 'S'); buttonMapping.put("update", 'U'); buttonMapping.put("toggle", 'T'); buttonMapping.put("delete", 'D'); buttonMapping.put("back", 'B');
        buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
        buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
        buttons.put("stats", new HotbarButtonConfig(Material.DIAMOND, "<aqua>Statistics"));
        buttons.put("update", new HotbarButtonConfig(Material.ANVIL, "<yellow>Update Description"));
        buttons.put("toggle-unpublish", new HotbarButtonConfig(Material.RED_WOOL, "<red>Unpublish"));
        buttons.put("toggle-republish", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Republish"));
        buttons.put("delete", new HotbarButtonConfig(Material.TNT, "<red>Delete Listing"));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
