package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class ButtonConfig {
    boolean enabled = false;
    int slot = -1;
    Material material = Material.STONE;
    int customModelData = 0;
    String name = "<white>Button";
    String nameOn = "<green>On";
    String nameOff = "<red>Off";
    List<String> lore = new ArrayList<String>();
    String itemModel = "";
    String craftEngineItem = "";

    // enabled is set when the button exists in gui-config.yml (or falls back to a global
    // definition); an unconfigured button is a placeholder returned by getButtonConfig.
    public boolean isEnabled() {
        return this.enabled;
    }

    public int getSlot() {
        return this.slot;
    }

    public Material getMaterial() {
        return this.material;
    }

    public int getCustomModelData() {
        return this.customModelData;
    }

    public String getName() {
        return this.name;
    }

    public String getNameOn() {
        return this.nameOn;
    }

    public String getNameOff() {
        return this.nameOff;
    }

    public List<String> getLore() {
        return this.lore;
    }

    public String getItemModel() {
        return this.itemModel;
    }

    public String getCraftEngineItem() {
        return this.craftEngineItem;
    }

    public ItemStack createItem() {
        return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData,
                this.itemModel, this.craftEngineItem);
    }
}
