package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Map;

final class GUISectionConfigLoader {
    private GUISectionConfigLoader() {
    }

    static void loadButtonMapping(ConfigurationSection section, Map<String, Character> mapping) {
        ConfigurationSection buttonMappingSection = section.getConfigurationSection("button-mapping");
        if (buttonMappingSection == null) {
            return;
        }
        for (String key : buttonMappingSection.getKeys(false)) {
            String value = buttonMappingSection.getString(key, " ");
            if (value != null && !value.isEmpty()) {
                char c = value.charAt(0);
                if (c != ' ') {
                    mapping.put(key, c);
                }
            }
        }
    }

    static void loadButtonsConfig(ConfigurationSection section, Map<String, GUIConfigManager.HotbarButtonConfig> buttons) {
        ConfigurationSection buttonsSection = section.getConfigurationSection("buttons");
        if (buttonsSection == null) {
            return;
        }
        for (String key : buttonsSection.getKeys(false)) {
            GUIConfigManager.HotbarButtonConfig btnConfig = buttons.get(key);
            if (btnConfig != null) {
                loadHotbarButtonConfig(buttonsSection, key, btnConfig);
                continue;
            }

            ConfigurationSection btnSection = buttonsSection.getConfigurationSection(key);
            if (btnSection == null) {
                continue;
            }
            Material material = matchMaterial(btnSection.getString("material", "STONE"), Material.STONE);
            String name = StringUtils.t(btnSection.getString("name", "<white>" + key));
            List<String> lore = StringUtils.t(btnSection.getStringList("lore"));
            int customModelData = btnSection.getInt("custom-model-data", 0);
            boolean glow = btnSection.getBoolean("glow", false);
            GUIConfigManager.HotbarButtonConfig newConfig = new GUIConfigManager.HotbarButtonConfig(material, name, lore, customModelData, glow);
            newConfig.itemModel = btnSection.getString("item-model", "");
            newConfig.craftEngineItem = btnSection.getString("craft-engine-item", "");
            buttons.put(key, newConfig);
        }
    }

    static void loadHotbarButtonConfig(ConfigurationSection section, String key, GUIConfigManager.HotbarButtonConfig config) {
        ConfigurationSection btnSection = section.getConfigurationSection(key);
        if (btnSection == null) {
            return;
        }
        config.material = matchMaterial(btnSection.getString("material", "STONE"), Material.STONE);
        config.name = StringUtils.t(btnSection.getString("name", config.name));
        config.lore = StringUtils.t(btnSection.getStringList("lore"));
        config.customModelData = btnSection.getInt("custom-model-data", 0);
        config.glow = btnSection.getBoolean("glow", config.glow);
        config.itemModel = btnSection.getString("item-model", config.itemModel);
        config.craftEngineItem = btnSection.getString("craft-engine-item", config.craftEngineItem);
    }

    static void loadEditorItemConfig(ConfigurationSection section, String key, GUIConfigManager.EditorItemConfig config) {
        ConfigurationSection itemSection = section.getConfigurationSection(key);
        if (itemSection == null) {
            return;
        }
        config.slot = itemSection.getInt("slot", config.slot);
        String materialStr = itemSection.getString("material");
        if (materialStr != null) {
            config.material = matchMaterial(materialStr, Material.STONE);
        }
        config.name = StringUtils.t(itemSection.getString("name", config.name));
        config.lore = StringUtils.t(itemSection.getStringList("lore"));
        config.customModelData = itemSection.getInt("custom-model-data", 0);
        config.glow = itemSection.getBoolean("glow", false);
        config.useInstrumentMaterial = itemSection.getBoolean("use-instrument-material", config.useInstrumentMaterial);
    }

    private static Material matchMaterial(String materialStr, Material fallback) {
        Material material = Material.matchMaterial(materialStr);
        return material != null ? material : fallback;
    }
}
