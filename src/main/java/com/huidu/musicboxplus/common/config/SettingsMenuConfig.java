package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class SettingsMenuConfig {
    String title = "<gold>Music Settings";
    String layout = "XBXSXTXNX\nXXXXCXXXX\nXXXXWXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

    public SettingsMenuConfig() {
        buttonMapping.put("bpm", 'B');
        buttonMapping.put("subdivision", 'S');
        buttonMapping.put("time-signature", 'T');
        buttonMapping.put("name", 'N');
        buttonMapping.put("clear", 'C');
        buttonMapping.put("save-close", 'W');
        buttonMapping.put("publish", 'P');
        buttonMapping.put("back", 'X');
        buttons.put("bpm", new HotbarButtonConfig(Material.REPEATER, "<gold>BPM Settings"));
        buttons.put("subdivision", new HotbarButtonConfig(Material.REDSTONE, "<red>Beat Subdivision"));
        buttons.put("time-signature", new HotbarButtonConfig(Material.CLOCK, "<aqua>Time Signature"));
        buttons.put("name", new HotbarButtonConfig(Material.NAME_TAG, "<yellow>Music Name"));
        buttons.put("clear", new HotbarButtonConfig(Material.TNT, "<red>Clear Notes"));
        buttons.put("save-close", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Save and Close"));
        buttons.put("publish", new HotbarButtonConfig(Material.EMERALD, "<green>Publish"));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }

    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
