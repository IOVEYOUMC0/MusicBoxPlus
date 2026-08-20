package com.huidu.musicboxplus.common.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ItemUtils {
    private static final Enchantment enchantment = Enchantment.UNBREAKING;

    // Glow is faked with an enchantment that is hidden again by ItemFlags, so the tooltip stays
    // clean. Returns the same instance that was passed in.
    public static ItemStack glow(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        applyGlow(meta);
        stack.setItemMeta(meta);
        return stack;
    }

    // Applies the glow enchant + hiding flags to an already-fetched meta, so callers building a
    // single meta can avoid a second getItemMeta/setItemMeta round-trip.
    public static void applyGlow(ItemMeta meta) {
        Objects.requireNonNull(meta).addEnchant(Objects.requireNonNull(enchantment), 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
    }

    public static ItemStack createStack(Material material, String name, @Nullable List<String> lore) {
        return ItemUtils.createStack(material, name, lore, 0);
    }

    // Like createStack(Material, String, List, int) but additionally honors a custom texture
    // source: a CraftEngine item id (craftEngineItem) wins and is used as the base item;
    // otherwise a vanilla item-model component is applied. Either may be null/blank to skip.
    public static ItemStack createStack(Material material, String name, @Nullable List<String> lore, int customModelData,
                                        @Nullable String itemModel, @Nullable String craftEngineItem) {
        ItemStack ceStack = (craftEngineItem != null && !craftEngineItem.trim().isEmpty())
                ? CraftEngineItemHelper.buildItem(craftEngineItem)
                : null;
        if (ceStack != null) {
            ceStack.setAmount(1);
            ItemMeta meta = ceStack.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    meta.displayName(MiniMessageUtils.processComponent(name));
                }
                if (lore != null) {
                    meta.lore(MiniMessageUtils.processComponents(lore));
                }
                meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_DESTROYS,
                    ItemFlag.HIDE_PLACED_ON,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_DYE,
                    ItemFlag.HIDE_ARMOR_TRIM
                );
                ceStack.setItemMeta(meta);
            }
            return ceStack;
        }
        ItemStack stack = createStack(material, name, lore, customModelData);
        return ItemModelHelper.setItemModel(stack, itemModel);
    }

    public static ItemStack createStack(Material material, String name, @Nullable List<String> lore, int customModelData) {
        ItemStack stack = new ItemStack(material);
        if (material == Material.AIR) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessageUtils.processComponent(name));
            if (lore != null) {
                meta.lore(MiniMessageUtils.processComponents(lore));
            }
            if (customModelData > 0) {
                meta.setCustomModelData(Integer.valueOf(customModelData));
            }
            meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_ARMOR_TRIM
            );
            stack.setItemMeta(meta);
        }
        return stack;
    }
    
    public static ItemStack createStack(Material material, Component name, @Nullable List<Component> lore) {
        return ItemUtils.createStack(material, name, lore, 0);
    }
    
    public static ItemStack createStack(Material material, Component name, @Nullable List<Component> lore, int customModelData) {
        ItemStack stack = new ItemStack(material);
        if (material == Material.AIR) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null) {
                meta.lore(lore);
            }
            if (customModelData > 0) {
                meta.setCustomModelData(Integer.valueOf(customModelData));
            }
            meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_UNBREAKABLE,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_ARMOR_TRIM
            );
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack addLore(ItemStack stack, List<String> additionalLore) {
        if (stack == null || additionalLore == null || additionalLore.isEmpty()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> currentLore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            currentLore.addAll(MiniMessageUtils.processComponents(additionalLore));
            meta.lore(currentLore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
    
    public static ItemStack addLoreComponents(ItemStack stack, List<Component> additionalLore) {
        if (stack == null || additionalLore == null || additionalLore.isEmpty()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<Component> currentLore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            currentLore.addAll(additionalLore);
            meta.lore(currentLore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static boolean isGlow(ItemStack item) {
        return item.getEnchantments().containsKey(enchantment);
    }

    public static ItemStack unGlow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Objects.requireNonNull(meta).removeEnchant(Objects.requireNonNull(enchantment));
        item.setItemMeta(meta);
        return item;
    }

    public static List<Material> getEndWith(String endWith) {
        return Arrays.stream(Material.values()).filter(s -> s.name().endsWith(endWith)).filter(Material::isItem).collect(Collectors.toList());
    }

    public static boolean isSimilar(ItemStack[] c1, ItemStack[] c2) {
        if (c1 == null || c2 == null) {
            return false;
        }
        if (c1.length != c2.length) {
            return false;
        }
        for (int i = 0; i < c1.length; ++i) {
            ItemStack i1 = c1[i];
            ItemStack i2 = c2[i];
            if (i1 == null && i2 == null) continue;
            if (i1 == null || i2 == null) {
                return false;
            }
            if (i1.isSimilar(i2)) continue;
            return false;
        }
        return true;
    }

    public static void groupInventory(Inventory inventory) {
        BukkitUtils.checkPrimary();
        List<ItemStack> songItems = new ArrayList<>();
        List<ItemStack> otherItems = new ArrayList<>();
        int size = inventory.getSize();
        for (int i = 0; i < size; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack == null || stack.getType().equals(Material.AIR)) continue;
            if (com.huidu.musicboxplus.api.MusicBoxAPI.isMusicBoxDisc(stack)) {
                songItems.add(stack);
                continue;
            }
            otherItems.add(stack);
        }
        inventory.clear();
        int current = 0;
        for (ItemStack stack : songItems) {
            inventory.setItem(current++, stack);
        }
        for (ItemStack stack : otherItems) {
            if (current >= size) {
                break;
            }
            inventory.setItem(current++, stack);
        }
    }

    public static int getFilledSlots(Inventory inventory) {
        int notEmpty = 0;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.getType().equals(Material.AIR)) continue;
            ++notEmpty;
        }
        return notEmpty;
    }

    public static void shiftInventory(Inventory inventory, int shift) {
        int invLength = inventory.getSize();
        int greatest = ItemUtils.greatestCommonDivisor(Math.abs(shift), invLength);
        for (int i = 0; i < greatest; ++i) {
            ItemStack buffer = inventory.getItem(i);
            int currentIndex = i;
            int movedIndex;
            if (shift > 0) {
                while (true) {
                    movedIndex = currentIndex + shift;
                    if (movedIndex >= invLength) {
                        movedIndex -= invLength;
                    }
                    if (movedIndex != i) {
                        inventory.setItem(currentIndex, inventory.getItem(movedIndex));
                        currentIndex = movedIndex;
                        continue;
                    }
                    break;
                }
            } else if (shift < 0) {
                while (true) {
                    movedIndex = currentIndex + shift;
                    if (movedIndex < 0) {
                        movedIndex = invLength + movedIndex;
                    }
                    if (movedIndex == i) break;
                    inventory.setItem(currentIndex, inventory.getItem(movedIndex));
                    currentIndex = movedIndex;
                }
            }
            inventory.setItem(currentIndex, buffer);
        }
    }

    private static int greatestCommonDivisor(int a, int b) {
        if (b == 0) {
            return a;
        }
        return ItemUtils.greatestCommonDivisor(b, a % b);
    }

    public static int findItem(Inventory inv, Predicate<ItemStack> predicate) {
        ListIterator<ItemStack> iter = inv.iterator();
        while (iter.hasNext()) {
            int index = iter.nextIndex();
            ItemStack stack = iter.next();
            if (!predicate.test(stack)) continue;
            return index;
        }
        return -1;
    }

    private ItemUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}

