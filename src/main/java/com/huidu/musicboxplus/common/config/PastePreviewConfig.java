package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PastePreviewConfig {
    String title = "<gold>Paste Preview: {count} Notes";
    String layout = "XXXXIXXXX\nXXXXCXXXX\nXXXXBXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public PastePreviewConfig() {
        buttonMapping.put("info", 'I'); buttonMapping.put("confirm", 'C'); buttonMapping.put("cancel", 'B');
        buttons.put("info", new HotbarButtonConfig(Material.BOOK, "<yellow>Preview", List.of("<gray>Review the pasted note count"), 0, false));
        buttons.put("confirm", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Confirm Paste", List.of("<gray>Paste notes into the current song"), 0, false));
        buttons.put("cancel", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Cancel", List.of("<gray>Discard this paste operation"), 0, false));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
