package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class EditMenuConfig {
    String title = "<gold>Music Editor Menu";
    String layout = "XXXCXXXXX\nXSXDXLXXX\nXXXXRXXXX\nXXXXBXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public EditMenuConfig() {
        buttonMapping.put("create", 'C'); buttonMapping.put("select", 'S'); buttonMapping.put("delete", 'D'); buttonMapping.put("rename", 'R'); buttonMapping.put("list", 'L'); buttonMapping.put("import", 'I'); buttonMapping.put("export", 'E'); buttonMapping.put("back", 'B');
        buttons.put("create", new HotbarButtonConfig(Material.EMERALD, "<green>Create Music", List.of("<gray>Create a new custom song"), 0, false));
        buttons.put("select", new HotbarButtonConfig(Material.NOTE_BLOCK, "<yellow>Select Music", List.of("<gray>Open and edit an existing custom song"), 0, false));
        buttons.put("delete", new HotbarButtonConfig(Material.REDSTONE, "<red>Delete Music", List.of("<gray>Delete one of your custom songs"), 0, false));
        buttons.put("rename", new HotbarButtonConfig(Material.NAME_TAG, "<gold>Rename Music", List.of("<gray>Change a custom song name"), 0, false));
        buttons.put("list", new HotbarButtonConfig(Material.BOOK, "<aqua>My Music List", List.of("<gray>Browse all of your custom songs"), 0, false));
        buttons.put("import", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<light_purple>Import Music File", List.of("<gray>Import an NBS or MIDI file"), 0, false));
        buttons.put("export", new HotbarButtonConfig(Material.MUSIC_DISC_11, "<aqua>Export as NBS", List.of("<gray>Export one of your songs"), 0, false));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back", new ArrayList<>(), 0, false));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
