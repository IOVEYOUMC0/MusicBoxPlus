package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class ClearConfirmConfig {
    final Map<String, Integer> buttonMappings = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public ClearConfirmConfig() {
        buttonMappings.put("confirm", 0); buttonMappings.put("info", 4); buttonMappings.put("cancel", 8);
        buttons.put("info", new HotbarButtonConfig(Material.BOOK, "<yellow>Warning", Arrays.asList("<gray>This will remove <red>{count} <gray>notes", "<red>This action cannot be undone!"), 0, false));
        buttons.put("confirm", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Confirm Clear", List.of("<gray>Delete the selected notes"), 0, false));
        buttons.put("cancel", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Cancel", List.of("<gray>Keep the current notes"), 0, false));
    }
    public Map<String, Integer> getButtonMappings() { return this.buttonMappings; }
    public Map<String, HotbarButtonConfig> getButtons() { return this.buttons; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Integer slot = buttonMappings.get(buttonType); return slot != null ? slot : -1; }
}
