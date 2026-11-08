package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PublishGUIConfig {
    String title = "<gold>Publish Music";
    String layout = "MMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nX<XCXR>XX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

    public PublishGUIConfig() {
        buttonMapping.put("music-item", 'M');
        buttonMapping.put("prev-page", '<');
        buttonMapping.put("close", 'C');
        buttonMapping.put("create", 'N');
        buttonMapping.put("revenue", 'R');
        buttonMapping.put("next-page", '>');
        buttonMapping.put("empty", 'X');
        buttons.put("music-item", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
        buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
        buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
        buttons.put("create", new HotbarButtonConfig(Material.EMERALD, "<green>Create Listing"));
        buttons.put("revenue", new HotbarButtonConfig(Material.EMERALD, "<green>Claim Revenue", List.of("<gray>Pending: <yellow>{amount}"), 0, false));
        buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
    }

    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return this.buttons.get(key); }

    public int getSlotForButton(String buttonType) {
        Character c = buttonMapping.get(buttonType);
        return c == null ? -1 : GUIConfigManager.getSlotForChar(layout, c);
    }

    public int getSlotForChar(char c) {
        return GUIConfigManager.getSlotForChar(layout, c);
    }

    public List<Integer> getSlotsForChar(char c) {
        return GUIConfigManager.getSlotsForChar(layout, c);
    }
}
