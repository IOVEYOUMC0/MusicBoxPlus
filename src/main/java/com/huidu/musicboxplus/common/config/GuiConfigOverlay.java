package com.huidu.musicboxplus.common.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

/**
 * Applies a per-language overlay on top of {@code gui-config.yml}.
 *
 * <p>The shipped GUI config is written in Chinese, so an English server showed Chinese titles and
 * button names no matter what {@code language} was set to: only the message files were localized.
 * An overlay file ({@code gui-config_en.yml}, {@code gui-config_zh_cn.yml}, ...) now replaces just
 * the strings it defines; everything it omits keeps the base value, so translations can land a
 * screen at a time instead of all at once.
 *
 * <p>Lists are replaced wholesale rather than merged element by element: a translated lore block is
 * written as a whole list, and mixing English and Chinese lines of the same hover text would be
 * worse than either.
 */
final class GuiConfigOverlay {

    /**
     * Language code to overlay file suffix. Chinese variants all read the simplified file, matching
     * how {@code LanguageConfig} picks its message file.
     */
    static String languageSuffix(String configuredLanguage) {
        if (configuredLanguage == null) {
            return "en";
        }
        String normalized = configuredLanguage.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        if (normalized.startsWith("language_")) {
            normalized = normalized.substring("language_".length());
        }
        if (normalized.endsWith(".yml")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        if (normalized.startsWith("zh")) {
            return "zh_cn";
        }
        if (normalized.startsWith("en")) {
            return "en";
        }
        return normalized.isEmpty() ? "en" : normalized;
    }

    static String overlayFileName(String configuredLanguage) {
        return "gui-config_" + languageSuffix(configuredLanguage) + ".yml";
    }

    /**
     * Copies every key the overlay defines into the base config, recursing into sections.
     *
     * <p>{@code base.set} writes into the top-level config even for a nested path, so scalar
     * overrides and whole-list replacements both need no special casing.
     */
    static void merge(YamlConfiguration base, ConfigurationSection overlay) {
        if (base == null || overlay == null) {
            return;
        }
        for (String key : overlay.getKeys(false)) {
            ConfigurationSection overlaySection = overlay.getConfigurationSection(key);
            if (overlaySection != null) {
                mergeSection(base, base.getConfigurationSection(key), key, overlaySection);
            } else {
                base.set(key, overlay.get(key));
            }
        }
    }

    private static void mergeSection(YamlConfiguration base, ConfigurationSection baseSection,
                                     String path, ConfigurationSection overlaySection) {
        for (String key : overlaySection.getKeys(false)) {
            String childPath = path + "." + key;
            ConfigurationSection overlayChild = overlaySection.getConfigurationSection(key);
            if (overlayChild == null) {
                base.set(childPath, overlaySection.get(key));
                continue;
            }
            // A section in the overlay needs a section in the base to merge into; when the base has
            // a scalar there, the overlay wins and replaces it outright.
            ConfigurationSection baseChild = baseSection == null ? null : baseSection.getConfigurationSection(key);
            if (baseChild == null) {
                base.set(childPath, null);
            }
            mergeSection(base, baseChild, childPath, overlayChild);
        }
    }

    private GuiConfigOverlay() {
    }
}
