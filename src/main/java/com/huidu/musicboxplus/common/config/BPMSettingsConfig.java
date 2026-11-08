package com.huidu.musicboxplus.common.config;

import org.bukkit.Material;
import java.util.*;

public class BPMSettingsConfig {
    String title = "<gold>BPM Settings";
    String layout = "XXXCXXXXX\nXXXXXXXXX\nXXXXXXXXX\nXXXXBXXXX";
    String stepIncreaseName = "<green>+{amount} BPM</green>";
    String stepDecreaseName = "<red>-{amount} BPM</red>";
    List<String> stepLore = new ArrayList<>(Arrays.asList(
        "<gray>Click to adjust BPM</gray>",
        "<gray>Current: </gray><yellow>{current}</yellow>",
        "<gray>After: </gray><yellow>{after}</yellow>"
    ));
    List<Integer> steps = new ArrayList<>(Arrays.asList(-50, -20, -10, -5, -1, 1, 5, 10, 20, 50));
    final Map<String, Character> buttonMapping = new HashMap<>();
    final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

    public BPMSettingsConfig() {
        buttonMapping.put("current-bpm", 'C');
        buttonMapping.put("reset", 'R');
        buttonMapping.put("back", 'B');
        buttons.put("current-bpm", new HotbarButtonConfig(Material.REPEATER, "<yellow>Current BPM: <green>{bpm}"));
        buttons.put("reset", new HotbarButtonConfig(Material.REDSTONE, "<red>Reset to 20"));
        buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
    }

    public String getTitle() { return this.title; }
    public String getLayout() { return this.layout; }
    public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
    public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
    public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
    public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    public String getStepIncreaseName() { return this.stepIncreaseName; }
    public String getStepDecreaseName() { return this.stepDecreaseName; }
    public List<String> getStepLore() { return this.stepLore; }
    public List<Integer> getSteps() { return this.steps; }
    public List<Integer> getSlotsForChars(String chars) {
        List<Integer> slots = new ArrayList<>();
        if (chars == null || chars.isEmpty()) return slots;
        String[] rows = GUIConfigManager.layoutRows(layout);
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (chars.indexOf(line.charAt(col)) >= 0) slots.add(row * 9 + col);
            }
        }
        return slots;
    }
}
