package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class HotbarButtonConfig {
    Material material;
    String name;
    List<String> lore = new ArrayList<String>();
    int customModelData;
    boolean glow;
    int slot;
    String itemModel = "";
    String craftEngineItem = "";

    public HotbarButtonConfig(Material material, String name) {
        this(material, name, new ArrayList<String>(), 0, false);
    }

    public HotbarButtonConfig(Material material, String name, List<String> lore, int customModelData, boolean glow) {
        this.material = material;
        this.name = name;
        this.lore = lore;
        this.customModelData = customModelData;
        this.glow = glow;
    }

    public Material getMaterial() { return this.material; }
    public String getName() { return this.name; }
    public List<String> getLore() { return this.lore; }
    public int getCustomModelData() { return this.customModelData; }
    public boolean isGlow() { return this.glow; }
    public int getSlot() { return this.slot; }
    public String getItemModel() { return this.itemModel; }
    public String getCraftEngineItem() { return this.craftEngineItem; }

    public ItemStack createItem() {
        return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData, this.itemModel, this.craftEngineItem);
    }
}
