package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class ShopContainerPurchaseConfirmConfig {
    String title = "<gold>Purchase Folder: {folder}";
    String layout = "XXXIXXXXX\nXPXXCXXXX\nXXXXBXXXX";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public ShopContainerPurchaseConfirmConfig() {
        buttonMapping.put("info", 'I');
        buttonMapping.put("price", 'P');
        buttonMapping.put("confirm", 'C');
        buttonMapping.put("no-money", 'C');
        buttonMapping.put("back", 'B');
        buttons.put("info", new HotbarButtonConfig(Material.CHEST, "<yellow>{folder}", List.of("<gray>Songs: <yellow>{count}"), 0, false));
        buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Total: <yellow>{price}", List.of("<gray>Your balance: <yellow>{balance}"), 0, false));
        buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Purchase", List.of("<gray>Buy every disc in this folder"), 0, false));
        buttons.put("no-money", new HotbarButtonConfig(Material.RED_WOOL, "<red>Not Enough Money", List.of("<gray>Need: <yellow>{need}"), 0, false));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
}
