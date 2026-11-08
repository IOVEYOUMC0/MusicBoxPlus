package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PublishConfirmConfig {
    String title = "<gold>Publish Music";
    String layout = "XXXIXXXXX\nXPXDXTXCX\nXXXXBXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public PublishConfirmConfig() {
        buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("description", 'D'); buttonMapping.put("tax-info", 'T'); buttonMapping.put("confirm", 'C'); buttonMapping.put("cancel", 'X');
        buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
        buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
        buttons.put("description", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<aqua>Description"));
        buttons.put("tax-info", new HotbarButtonConfig(Material.REDSTONE, "<red>Tax Info"));
        buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Publish"));
        buttons.put("cancel", new HotbarButtonConfig(Material.RED_WOOL, "<red>Cancel"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
