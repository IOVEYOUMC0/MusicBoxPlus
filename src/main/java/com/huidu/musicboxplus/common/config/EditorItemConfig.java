package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class EditorItemConfig {
    Material material;
    String name;
    List<String> lore = new ArrayList<String>();
    int customModelData;
    boolean glow;
    int slot;
    boolean useInstrumentMaterial;

    public EditorItemConfig(Material material, String name) {
        this.material = material;
        this.name = name;
    }

    public Material getMaterial() { return this.material; }
    public String getName() { return this.name; }
    public List<String> getLore() { return this.lore; }
    public int getCustomModelData() { return this.customModelData; }
    public boolean isGlow() { return this.glow; }
    public int getSlot() { return this.slot; }
    public boolean isUseInstrumentMaterial() { return this.useInstrumentMaterial; }

    public ItemStack createItem() {
        return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData);
    }
}
