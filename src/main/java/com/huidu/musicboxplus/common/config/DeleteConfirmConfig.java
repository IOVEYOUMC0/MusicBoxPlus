package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class DeleteConfirmConfig {
    String title = "<red>Confirm delete: {name}</red>";
    String layout = "XXXXIXXXX\nXXCXXXBXX\nXXXXXXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public DeleteConfirmConfig() {
        buttonMapping.put("warning", 'I');
        buttonMapping.put("confirm", 'C');
        buttonMapping.put("cancel", 'B');
        buttons.put("warning", new HotbarButtonConfig(Material.RED_TERRACOTTA, "<red>Delete </red><yellow>{name}</yellow><red>?</red>", List.of("<gray>This action cannot be undone</gray>"), 0, false));
        buttons.put("confirm", new HotbarButtonConfig(Material.GREEN_CONCRETE, "<green>Confirm</green>", List.of("<gray>Delete this item</gray>"), 0, false));
        buttons.put("cancel", new HotbarButtonConfig(Material.RED_CONCRETE, "<red>Cancel</red>", List.of("<gray>Keep this item</gray>"), 0, false));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
