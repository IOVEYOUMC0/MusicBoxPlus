package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PlayerShopConfig {
    String title = "<gold>Player Music Shop";
    String layout = "MMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nX<XCXSY>X";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public PlayerShopConfig() {
        buttonMapping.put("music-item", 'M'); buttonMapping.put("prev-page", '<'); buttonMapping.put("close", 'C'); buttonMapping.put("search", 'S'); buttonMapping.put("my-music", 'Y'); buttonMapping.put("next-page", '>'); buttonMapping.put("empty", 'X');
        buttons.put("music-item", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
        buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
        buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
        buttons.put("search", new HotbarButtonConfig(Material.COMPASS, "<aqua>Search"));
        buttons.put("my-music", new HotbarButtonConfig(Material.CHEST, "<green>My Published Music"));
        buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
}
