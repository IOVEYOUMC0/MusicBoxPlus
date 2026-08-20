package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// GUI button definitions: global buttons plus per-GUI slot overrides, with fallback lookup.
final class GUIButtonRegistry {
    private final Map<String, GUIConfigManager.ButtonConfig> buttonConfigs = new ConcurrentHashMap<>();

    Map<String, GUIConfigManager.ButtonConfig> getButtonConfigs() {
        return this.buttonConfigs;
    }

    void load(YamlConfiguration config) {
        this.buttonConfigs.clear();
        this.loadGlobalButtons(config);
        this.loadGuiButtonSlots(config);
    }

    GUIConfigManager.ButtonConfig getButtonConfig(String guiName, String buttonName) {
        GUIConfigManager.ButtonConfig parentButton;
        GUIConfigManager.ButtonConfig guiButton = this.buttonConfigs.get(guiName + "." + buttonName);
        if (guiButton != null && guiButton.getMaterial() != Material.STONE) {
            return guiButton;
        }

        GUIConfigManager.ButtonConfig globalButton = this.buttonConfigs.get("global." + buttonName);
        if (globalButton != null) {
            return globalButton;
        }

        String altName = buttonName.replace("-", "");
        globalButton = this.buttonConfigs.get("global." + altName);
        if (globalButton != null) {
            return globalButton;
        }

        if (buttonName.equals("parent") && (parentButton = this.buttonConfigs.get("global.parent-folder")) != null) {
            return parentButton;
        }

        if (!buttonName.contains("-")) {
            for (String key : this.buttonConfigs.keySet()) {
                if (!key.startsWith("global.") || !key.replace("-", "").equalsIgnoreCase(altName)) {
                    continue;
                }
                return this.buttonConfigs.get(key);
            }
        }

        return new GUIConfigManager.ButtonConfig();
    }

    ItemStack createButtonItem(String guiName, String buttonName) {
        GUIConfigManager.ButtonConfig buttonConfig = this.getButtonConfig(guiName, buttonName);
        if (!buttonConfig.isEnabled()) {
            return null;
        }
        return ItemUtils.createStack(
            buttonConfig.getMaterial(),
            buttonConfig.getName(),
            buttonConfig.getLore(),
            buttonConfig.getCustomModelData()
        );
    }

    ItemStack createButtonItem(String guiName, String buttonName, String... replacements) {
        GUIConfigManager.ButtonConfig buttonConfig = this.getButtonConfig(guiName, buttonName);
        if (!buttonConfig.isEnabled()) {
            return null;
        }

        String name = buttonConfig.getName();
        List<String> lore = buttonConfig.getLore();
        if (replacements != null && replacements.length >= 2) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                if (name != null) {
                    name = name.replace(placeholder, value);
                }
                if (lore != null) {
                    ArrayList<String> newLore = new ArrayList<>();
                    for (String line : lore) {
                        newLore.add(line.replace(placeholder, value));
                    }
                    lore = newLore;
                }
            }
        }

        return ItemUtils.createStack(buttonConfig.getMaterial(), name, lore, buttonConfig.getCustomModelData());
    }

    int getButtonSlot(String guiName, String buttonName) {
        return this.getButtonConfig(guiName, buttonName).getSlot();
    }

    private void loadGlobalButtons(YamlConfiguration config) {
        ConfigurationSection globalButtonsSection = config.getConfigurationSection("buttons");
        if (globalButtonsSection == null) {
            return;
        }

        for (String buttonName : globalButtonsSection.getKeys(false)) {
            ConfigurationSection buttonSection = globalButtonsSection.getConfigurationSection(buttonName);
            if (buttonSection == null) {
                continue;
            }
            GUIConfigManager.ButtonConfig buttonConfig = new GUIConfigManager.ButtonConfig();
            buttonConfig.enabled = true;
            buttonConfig.slot = -1;
            buttonConfig.material = Material.matchMaterial(buttonSection.getString("material", "STONE"));
            if (buttonConfig.material == null) {
                buttonConfig.material = Material.STONE;
            }
            buttonConfig.customModelData = buttonSection.getInt("custom-model-data", 0);
            buttonConfig.name = StringUtils.t(buttonSection.getString("name", "<white>Button"));
            buttonConfig.lore = StringUtils.t(buttonSection.getStringList("lore"));
            this.buttonConfigs.put("global." + buttonName, buttonConfig);
        }
    }

    private void loadGuiButtonSlots(YamlConfiguration config) {
        ConfigurationSection guiSection = config.getConfigurationSection("gui");
        if (guiSection == null) {
            return;
        }

        for (String guiName : guiSection.getKeys(false)) {
            ConfigurationSection section = guiSection.getConfigurationSection(guiName);
            ConfigurationSection buttonSlotsSection = section != null ? section.getConfigurationSection("button-slots") : null;
            if (buttonSlotsSection == null) {
                continue;
            }

            for (String buttonName : buttonSlotsSection.getKeys(false)) {
                int slot = buttonSlotsSection.getInt(buttonName, -1);
                GUIConfigManager.ButtonConfig globalConfig = this.buttonConfigs.get("global." + buttonName);
                if (globalConfig != null) {
                    GUIConfigManager.ButtonConfig guiButtonConfig = new GUIConfigManager.ButtonConfig();
                    guiButtonConfig.enabled = globalConfig.enabled;
                    guiButtonConfig.slot = slot;
                    guiButtonConfig.material = globalConfig.material;
                    guiButtonConfig.customModelData = globalConfig.customModelData;
                    guiButtonConfig.name = globalConfig.name;
                    guiButtonConfig.lore = globalConfig.lore;
                    this.buttonConfigs.put(guiName + "." + buttonName, guiButtonConfig);
                    continue;
                }

                GUIConfigManager.ButtonConfig buttonConfig = new GUIConfigManager.ButtonConfig();
                buttonConfig.enabled = true;
                buttonConfig.slot = slot;
                this.buttonConfigs.put(guiName + "." + buttonName, buttonConfig);
            }
        }
    }
}
