package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PurchaseConfirmConfig {
    String title = "<gold>Purchase: {name}";
    String layout = "XXXIXXXXX\nXPXXCXXXX\nXXXXBXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public PurchaseConfirmConfig() {
        buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("confirm", 'C'); buttonMapping.put("back", 'B');
        buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
        buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
        buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Purchase"));
        buttons.put("no-money", new HotbarButtonConfig(Material.RED_WOOL, "<red>Not Enough Money"));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
