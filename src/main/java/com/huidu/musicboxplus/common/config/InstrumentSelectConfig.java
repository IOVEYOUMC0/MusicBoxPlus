package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class InstrumentSelectConfig {
    String layout = "IIIIIIIII\nIIIIIIIII\nIIIIIIIII\nN<XPX>X>B";
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

    public InstrumentSelectConfig() {
        buttonMapping.put("note-info", 'N');
        buttonMapping.put("prev-page", '<');
        buttonMapping.put("play-preview", 'P');
        buttonMapping.put("next-page", '>');
        buttonMapping.put("back", 'B');
        buttonMapping.put("instrument", 'I');
        buttons.put("note-info", new HotbarButtonConfig(Material.NOTE_BLOCK, "<white>Note <yellow>{note}"));
        buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
        buttons.put("play-preview", new HotbarButtonConfig(Material.JUKEBOX, "<green>Preview From Current Note"));
        buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }

    public String getTitle() {
        String title = "<gold>Select Instrument";
        return title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
}
