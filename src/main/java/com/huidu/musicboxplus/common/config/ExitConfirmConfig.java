package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class ExitConfirmConfig {
    String title = "<red>Unsaved Changes";
    String layout = "XXXXXXXXX\nXISXDXXXX\nXXXXCXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public ExitConfirmConfig() {
        buttonMapping.put("info", 'I'); buttonMapping.put("save", 'S'); buttonMapping.put("discard", 'D'); buttonMapping.put("cancel", 'C');
        buttons.put("info", new HotbarButtonConfig(Material.PAPER, "<yellow>{name}", Arrays.asList("<gray>You have unsaved changes", "<gray>Choose what to do before exiting"), 0, false));
        buttons.put("save", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Save and Exit", List.of("<gray>Save changes before closing"), 0, false));
        buttons.put("discard", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Discard Changes", List.of("<gray>Close without saving"), 0, false));
        buttons.put("cancel", new HotbarButtonConfig(Material.GRAY_STAINED_GLASS_PANE, "<gray>Cancel", List.of("<gray>Return to the editor"), 0, false));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
