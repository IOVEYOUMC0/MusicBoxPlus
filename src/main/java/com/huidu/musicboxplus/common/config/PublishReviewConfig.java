package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class PublishReviewConfig {
    String title = "<gold>Publish Review";
    String layout = "RRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nX<XCXFX>";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
    public PublishReviewConfig() {
        buttonMapping.put("review-item", 'R'); buttonMapping.put("prev-page", '<'); buttonMapping.put("close", 'C'); buttonMapping.put("refresh", 'F'); buttonMapping.put("next-page", '>');
        buttons.put("review-item", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<yellow>{name}"));
        buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
        buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
        buttons.put("refresh", new HotbarButtonConfig(Material.COMPASS, "<aqua>Refresh"));
        buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
    }
    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
}
