package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.module.edit.MusicEditGUI;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class PublishConfirmGUI implements InventoryHolder {

    private final Player player;
    private final PlayerMusic music;
    private final PublishGUI parentGUI;
    private final Inventory inventory;
    private final GUIConfigManager.PublishConfirmConfig config;
    private double currentPrice = 100;
    private String description = "";

    public PublishConfirmGUI(Player player, PlayerMusic music, PublishGUI parentGUI) {
        this.player = player;
        this.music = music;
        this.parentGUI = parentGUI;
        this.config = GUIConfigManager.getInstance().getPublishConfirmConfig();
        this.currentPrice = clampPrice(this.currentPrice);
        this.description = sanitizeDescription(music.getDescription());
        String title = config.getTitle().replace("{name}", music.getName());
        this.inventory = Bukkit.createInventory(this, 27, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int infoSlot = config.getSlotForButton("info");
        if (infoSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig infoConfig = config.getButton("info");
            List<String> lore = new ArrayList<>();
            for (String line : infoConfig.getLore()) {
                lore.add(line.replace("{name}", music.getName())
                        .replace("{notes}", String.valueOf(music.getNoteCount()))
                        .replace("{bpm}", String.valueOf(music.getBpm()))
                        .replace("{timeSignature}", music.getTimeSignature().toString()));
            }
            String name = infoConfig.getName().replace("{name}", music.getName());
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), name, lore, infoConfig.getCustomModelData());
            inventory.setItem(infoSlot, infoItem);
        }

        int priceSlot = config.getSlotForButton("price");
        if (priceSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig priceConfig = config.getButton("price");
            List<String> lore = new ArrayList<>();
            double minPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
            double maxPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();
            for (String line : priceConfig.getLore()) {
                lore.add(line.replace("{price}", String.format("%.0f", currentPrice))
                        .replace("{minPrice}", String.format("%.0f", minPrice))
                        .replace("{maxPrice}", String.format("%.0f", maxPrice)));
            }
            String name = priceConfig.getName().replace("{price}", String.format("%.0f", currentPrice));
            ItemStack priceItem = ItemUtils.createStack(priceConfig.getMaterial(), name, lore, priceConfig.getCustomModelData());
            inventory.setItem(priceSlot, priceItem);
        }

        int descSlot = config.getSlotForButton("description");
        if (descSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig descConfig = config.getButton("description");
            String descriptionText = description.isEmpty() ? Lang.PUBLISH_DESCRIPTION_EMPTY.toString() : description;
            List<String> lore = new ArrayList<>();
            for (String line : descConfig.getLore()) {
                lore.add(line.replace("{description}", descriptionText));
            }
            String name = descConfig.getName().replace("{description}", descriptionText);
            ItemStack descItem = ItemUtils.createStack(descConfig.getMaterial(), name, lore, descConfig.getCustomModelData());
            inventory.setItem(descSlot, descItem);
        }

        int taxSlot = config.getSlotForButton("tax-info");
        if (taxSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig taxConfig = config.getButton("tax-info");
            double taxRate = MusicBox.getInstance().getConfigObject().getPublishConfig().getTaxRate();
            double authorRevenue = currentPrice * (1 - taxRate);
            List<String> lore = new ArrayList<>();
            for (String line : taxConfig.getLore()) {
                lore.add(line.replace("{taxRate}", String.format("%.0f", taxRate * 100))
                        .replace("{price}", String.format("%.0f", currentPrice))
                        .replace("{revenue}", String.format("%.0f", authorRevenue)));
            }
            ItemStack taxInfoItem = ItemUtils.createStack(taxConfig.getMaterial(), taxConfig.getName(), lore, taxConfig.getCustomModelData());
            inventory.setItem(taxSlot, taxInfoItem);
        }

        int confirmSlot = config.getSlotForButton("confirm");
        if (confirmSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig confirmConfig = config.getButton("confirm");
            ItemStack confirmItem = confirmConfig.createItem();
            inventory.setItem(confirmSlot, confirmItem);
        }

        int cancelSlot = config.getSlotForButton("cancel");
        if (cancelSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig cancelConfig = config.getButton("cancel");
            ItemStack cancelItem = cancelConfig.createItem();
            inventory.setItem(cancelSlot, cancelItem);
        }
    }

    public void handleClick(int slot, boolean isRightClick, boolean isShiftClick) {
        int priceSlot = config.getSlotForButton("price");
        int descSlot = config.getSlotForButton("description");
        int confirmSlot = config.getSlotForButton("confirm");
        int cancelSlot = config.getSlotForButton("cancel");

        if (slot == priceSlot) {
            double minPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
            double maxPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();
            
            double change;
            if (isShiftClick) {
                change = isRightClick ? -100 : 100;
            } else {
                change = isRightClick ? -10 : 10;
            }
            
            currentPrice = Math.max(minPrice, Math.min(maxPrice, currentPrice + change));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            updateInventory();
            return;
        }

        if (slot == descSlot) {
            MusicEditGUI.startTextInput(
                    player,
                    music,
                    "publish_description",
                    descriptionInput -> {
                        String safeDescription = sanitizeDescription(descriptionInput);
                        setDescription(safeDescription);
                        MessageUtils.send(player, Lang.PUBLISH_DESCRIPTION_SET_MSG, "{description}", safeDescription);
                        open();
                    },
                    this::open
            );
            return;
        }

        if (slot == confirmSlot) {
            publishMusic();
            return;
        }

        if (slot == cancelSlot) {
            player.closeInventory();
            parentGUI.open();
        }
    }

    private void publishMusic() {
        if (!MusicBox.getInstance().getConfigObject().getPublishConfig().isEnable()) {
            MessageUtils.send(player, Lang.PUBLISH_SYSTEM_DISABLED);
            return;
        }

        int maxPublished = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPublishedPerPlayer();
        List<PublishedMusic> currentPublished = PublishedMusicManager.getInstance().getPublishedByAuthor(player.getUniqueId());
        boolean updatesExistingListing = currentPublished.stream()
                .anyMatch(p -> music.getUniqueId().equals(p.getOriginalMusicId()));
        long availableCount = currentPublished.stream().filter(PublishedMusic::isAvailable).count();

        if (!updatesExistingListing && availableCount >= maxPublished) {
            MessageUtils.send(player, Lang.PUBLISH_LIMIT_REACHED_MSG, "{limit}", String.valueOf(maxPublished));
            return;
        }

        PublishedMusicManager.PublishResult result = PublishedMusicManager.getInstance()
                .publishMusic(music, currentPrice, player, description);

        switch (result) {
            case SUCCESS:
                MessageUtils.send(player, Lang.PUBLISH_SUCCESS_MSG, "{name}", music.getName());
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
                player.closeInventory();
                parentGUI.refresh();
                parentGUI.open();
                break;
            case INVALID_PRICE:
                MessageUtils.send(player, Lang.PUBLISH_PRICE_INVALID);
                break;
            case PRICE_OUT_OF_RANGE:
                MessageUtils.send(player, Lang.PUBLISH_PRICE_OUT_OF_RANGE_MSG.toString(
                        "{min}", String.format("%.0f", MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice()),
                        "{max}", String.format("%.0f", MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice())
                ));
                break;
            default:
                MessageUtils.send(player, Lang.PUBLISH_FAILED_MSG);
                break;
        }
    }

    public void setDescription(String description) {
        this.description = description;
        updateInventory();
    }

    private String sanitizeDescription(String input) {
        if (input == null) {
            return "";
        }
        String sanitized = input.replaceAll("[<>\"'&\\x00-\\x1f\\x7f-\\x9f\\u00a7\\r\\n]", "");
        sanitized = sanitized.replaceAll("[\\u200B-\\u200D\\uFEFF\\u200E\\u200F]", "");
        if (sanitized.length() > 256) {
            sanitized = sanitized.substring(0, 256);
        }
        return sanitized.trim();
    }

    private double clampPrice(double price) {
        double minPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
        double maxPrice = MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();
        return Math.max(minPrice, Math.min(maxPrice, price));
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
