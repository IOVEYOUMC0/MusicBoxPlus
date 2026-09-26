package com.huidu.musicboxplus.module.gui.layout;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.PlayerClickAction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class LayoutParser {
    private final GUI gui;
    private final String guiName;
    private final GUIConfigManager configManager;
    private final Map<Character, ButtonDefinition> buttonDefinitions;
    private final Map<Character, Integer> slotCache;
    private final Map<Character, List<Integer>> slotsForCharCache;

    public LayoutParser(GUI gui, String guiName) {
        this.gui = gui;
        this.guiName = guiName;
        this.configManager = GUIConfigManager.getInstance();
        this.buttonDefinitions = new HashMap<Character, ButtonDefinition>();
        this.slotCache = new HashMap<Character, Integer>();
        this.slotsForCharCache = new HashMap<Character, List<Integer>>();
    }

    public void registerButton(char character, Supplier<ItemStack> itemSupplier, Supplier<InventoryAction> actionSupplier) {
        this.buttonDefinitions.put(Character.valueOf(character), new ButtonDefinition(itemSupplier, actionSupplier));
    }

    public void registerButton(int slot, Supplier<ItemStack> itemSupplier, Supplier<InventoryAction> actionSupplier) {
        ItemStack item = itemSupplier.get();
        InventoryAction action = actionSupplier.get();
        if (item != null && action != null) {
            this.gui.addItem(slot, item, action);
        }
    }

    public void registerButton(char character, Function<Integer, ItemStack> itemFunction, Supplier<InventoryAction> actionSupplier) {
        this.buttonDefinitions.put(Character.valueOf(character), new ButtonDefinition(itemFunction, actionSupplier));
    }

    public void registerSimpleButton(char character, String buttonName, Runnable action) {
        this.registerButton(character, () -> this.configManager.createButtonItem(this.guiName, buttonName), () -> new ClickAction(action));
    }

    public void registerPlayerButton(char character, String buttonName, Consumer<Player> action) {
        this.registerButton(character, () -> this.configManager.createButtonItem(this.guiName, buttonName), () -> new PlayerClickAction(action));
    }

    public void parseAndApply(String layoutPattern) {
        if (layoutPattern == null || layoutPattern.isEmpty()) {
            return;
        }
        String[] lines = layoutPattern.split("\n");
        for (int row = 0; row < lines.length; ++row) {
            String line = lines[row];
            for (int col = 0; col < Math.min(line.length(), 9); ++col) {
                char character = line.charAt(col);
                int slot = row * 9 + col;
                if (!this.slotCache.containsKey(Character.valueOf(character)) || character != 'X') {
                    this.slotCache.put(Character.valueOf(character), slot);
                }
                this.applyButton(character, slot);
            }
        }
    }

    private void applyButton(char character, int slot) {
        ItemStack item;
        ButtonDefinition definition = this.buttonDefinitions.get(Character.valueOf(character));
        if (definition == null) {
            return;
        }
        if (definition.itemFunction != null) {
            item = definition.itemFunction.apply(slot);
        } else if (definition.itemSupplier != null) {
            item = definition.itemSupplier.get();
        } else {
            return;
        }
        // A null item still left its click action registered, so the empty slot stayed clickable.
        if (item == null) {
            this.gui.removeItem(slot);
            return;
        }
        InventoryAction action = null;
        if (definition.actionSupplier != null) {
            action = definition.actionSupplier.get();
        }
        this.gui.addItem(slot, item, action);
    }

    public int getSlot(char character) {
        return this.slotCache.getOrDefault(Character.valueOf(character), -1);
    }

    public List<Integer> getSlotsForChar(char character) {
        List<Integer> cached = this.slotsForCharCache.get(Character.valueOf(character));
        if (cached != null) {
            return cached;
        }
        ArrayList<Integer> slots = new ArrayList<Integer>();
        String layout = this.configManager.getGUILayout(this.guiName);
        if (layout == null || layout.isEmpty()) {
            this.slotsForCharCache.put(Character.valueOf(character), java.util.Collections.emptyList());
            return java.util.Collections.emptyList();
        }
        String[] lines = layout.split("\n");
        for (int row = 0; row < lines.length; ++row) {
            String line = lines[row];
            for (int col = 0; col < Math.min(line.length(), 9); ++col) {
                if (line.charAt(col) != character) continue;
                slots.add(row * 9 + col);
            }
        }
        List<Integer> result = java.util.Collections.unmodifiableList(slots);
        this.slotsForCharCache.put(Character.valueOf(character), result);
        return result;
    }

    public static String createDefaultLayout(int rows, char backgroundChar) {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < rows; ++row) {
            for (int col = 0; col < 9; ++col) {
                sb.append(backgroundChar);
            }
            if (row >= rows - 1) continue;
            sb.append("\n");
        }
        return sb.toString();
    }

    public void setSlot(int slot, ItemStack item, InventoryAction action) {
        this.gui.addItem(slot, item, action);
    }

    public void clearSlot(int slot) {
        this.gui.addItem(slot, null, null);
    }

    private static class ButtonDefinition {
        final Supplier<ItemStack> itemSupplier;
        final Function<Integer, ItemStack> itemFunction;
        final Supplier<InventoryAction> actionSupplier;

        ButtonDefinition(Supplier<ItemStack> itemSupplier, Supplier<InventoryAction> actionSupplier) {
            this.itemSupplier = itemSupplier;
            this.itemFunction = null;
            this.actionSupplier = actionSupplier;
        }

        ButtonDefinition(Function<Integer, ItemStack> itemFunction, Supplier<InventoryAction> actionSupplier) {
            this.itemSupplier = null;
            this.itemFunction = itemFunction;
            this.actionSupplier = actionSupplier;
        }
    }
}

