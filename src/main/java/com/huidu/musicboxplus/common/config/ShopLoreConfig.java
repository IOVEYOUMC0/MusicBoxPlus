package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.configuration.ConfigurationSection;
import java.util.*;

public class ShopLoreConfig {
    List<String> shopLore = new ArrayList<>();
    List<String> getLore = new ArrayList<>();
    List<String> searchLore = new ArrayList<>();
    List<String> recentLore = new ArrayList<>();
    List<String> containerPlayAllLore = new ArrayList<>();
    List<String> containerBuyAllLore = new ArrayList<>();

    public ShopLoreConfig() {
        shopLore.add("<gray>Left click to buy this music");
        shopLore.add("<gray>Price: <yellow>{price}");
        getLore.add("<gray>Left click to get this music");
        searchLore.add("<gray>Left click to play");
        recentLore.add("<gray>Left click to play");
        containerPlayAllLore.add("<yellow>Right click to play all");
        containerBuyAllLore.add("<yellow>Right click to buy all");
        containerBuyAllLore.add("<gray>Total: <yellow>{price}");
    }

    public List<String> getShopLore() { return this.shopLore; }
    public List<String> getGetLore() { return this.getLore; }
    public List<String> getSearchLore() { return this.searchLore; }
    public List<String> getRecentLore() { return this.recentLore; }
    public List<String> getContainerPlayAllLore() { return this.containerPlayAllLore; }
    public List<String> getContainerBuyAllLore() { return this.containerBuyAllLore; }

    public void loadFromConfig(ConfigurationSection section) {
        if (section == null) return;
        loadStringList(section, "shop-lore", shopLore);
        loadStringList(section, "get-lore", getLore);
        loadStringList(section, "search-lore", searchLore);
        loadStringList(section, "recent-lore", recentLore);
        loadStringList(section, "container-play-all-lore", containerPlayAllLore);
        loadStringList(section, "container-buy-all-lore", containerBuyAllLore);
    }

    private void loadStringList(ConfigurationSection section, String path, List<String> target) {
        List<String> list = section.getStringList(path);
        if (list != null && !list.isEmpty()) {
            target.clear();
            target.addAll(StringUtils.t(list));
        }
    }
}
