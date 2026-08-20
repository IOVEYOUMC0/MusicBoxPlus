package com.huidu.musicboxplus.common.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

public final class ItemModelHelper {
    private static final Method SET_ITEM_MODEL_METHOD = findSetItemModelMethod();

    private ItemModelHelper() {
    }

    public static boolean isSupported() {
        return SET_ITEM_MODEL_METHOD != null;
    }

    public static ItemStack setItemModel(ItemStack item, String itemModel) {
        if (item == null || itemModel == null || itemModel.trim().isEmpty() || SET_ITEM_MODEL_METHOD == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (applyItemModel(meta, itemModel)) {
            item.setItemMeta(meta);
        }
        return item;
    }

    // Applies the model to an already-fetched meta, so callers that are building one meta can
    // avoid a second getItemMeta/setItemMeta round-trip (each one deep-copies the meta).
    // Returns false when there is nothing to apply.
    public static boolean applyItemModel(ItemMeta meta, String itemModel) {
        if (meta == null || itemModel == null || itemModel.trim().isEmpty() || SET_ITEM_MODEL_METHOD == null) {
            return false;
        }
        NamespacedKey key = NamespacedKey.fromString(itemModel.trim());
        if (key == null) {
            return false;
        }
        try {
            SET_ITEM_MODEL_METHOD.invoke(meta, key);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Method findSetItemModelMethod() {
        try {
            return ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
