package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.module.edit.MusicEditGUI;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ManagePublishedGUI implements InventoryHolder {

    private final Player player;
    private final PublishedMusic published;
    private final PublishGUI parentGUI;
    private final Inventory inventory;
    private final GUIConfigManager.ManagePublishedConfig config;

    public ManagePublishedGUI(Player player, PublishedMusic published, PublishGUI parentGUI) {
        this.player = player;
        this.published = published;
        this.parentGUI = parentGUI;
        this.config = GUIConfigManager.getInstance().getManagePublishedConfig();
        String title = config.getTitle().replace("{name}", published.getName());
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
                lore.add(line.replace("{name}", published.getName())
                        .replace("{author}", published.getAuthor())
                        .replace("{notes}", String.valueOf(published.getNoteCount()))
                        .replace("{bpm}", String.valueOf(published.getBpm()))
                        .replace("{publishedAt}", formatDate(published.getPublishedAt()))
                        .replace("{updatedAt}", formatDate(published.getUpdatedAt())));
            }
            String name = infoConfig.getName().replace("{name}", published.getName());
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), name, lore, infoConfig.getCustomModelData());
            inventory.setItem(infoSlot, infoItem);
        }

        int priceSlot = config.getSlotForButton("price");
        if (priceSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig priceConfig = config.getButton("price");
            List<String> lore = new ArrayList<>();
            for (String line : priceConfig.getLore()) {
                lore.add(line.replace("{price}", String.format("%.0f", published.getPrice()))
                        .replace("{sales}", String.valueOf(published.getSalesCount()))
                        .replace("{revenue}", String.format("%.2f", published.getTotalRevenue())));
            }
            String name = priceConfig.getName().replace("{price}", String.format("%.0f", published.getPrice()));
            ItemStack priceItem = ItemUtils.createStack(priceConfig.getMaterial(), name, lore, priceConfig.getCustomModelData());
            inventory.setItem(priceSlot, priceItem);
        }

        int statsSlot = config.getSlotForButton("stats");
        if (statsSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig statsConfig = config.getButton("stats");
            String status;
            if (published.isPending()) {
                status = Lang.PUBLISH_STATUS_PENDING.toString();
            } else if (!published.isApproved()) {
                status = Lang.PUBLISH_STATUS_REJECTED.toString();
            } else {
                status = published.isAvailable()
                        ? Lang.PUBLISH_STATUS_PUBLISHED.toString()
                        : Lang.PUBLISH_STATUS_UNPUBLISHED.toString();
            }
            List<String> lore = new ArrayList<>();
            for (String line : statsConfig.getLore()) {
                lore.add(line.replace("{sales}", String.valueOf(published.getSalesCount()))
                        .replace("{revenue}", String.format("%.2f", published.getTotalRevenue()))
                        .replace("{status}", status));
            }
            ItemStack statsItem = ItemUtils.createStack(statsConfig.getMaterial(), statsConfig.getName(), lore, statsConfig.getCustomModelData());
            inventory.setItem(statsSlot, statsItem);
        }

        int updateSlot = config.getSlotForButton("update");
        if (updateSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig updateConfig = config.getButton("update");
            ItemStack updateItem = updateConfig.createItem();
            inventory.setItem(updateSlot, updateItem);
        }

        int toggleSlot = config.getSlotForButton("toggle");
        if (toggleSlot >= 0) {
            String toggleKey = published.isAvailable() ? "toggle-unpublish" : "toggle-republish";
            GUIConfigManager.HotbarButtonConfig toggleConfig = config.getButton(toggleKey);
            if (toggleConfig != null) {
                ItemStack toggleItem = toggleConfig.createItem();
                inventory.setItem(toggleSlot, toggleItem);
            }
        }

        int deleteSlot = config.getSlotForButton("delete");
        if (deleteSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig deleteConfig = config.getButton("delete");
            ItemStack deleteItem = deleteConfig.createItem();
            inventory.setItem(deleteSlot, deleteItem);
        }

        int backSlot = config.getSlotForButton("back");
        if (backSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig backConfig = config.getButton("back");
            ItemStack backItem = backConfig.createItem();
            inventory.setItem(backSlot, backItem);
        }
    }

    private String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new Date(timestamp));
    }

    public void handleClick(int slot, boolean isShiftClick) {
        int priceSlot = config.getSlotForButton("price");
        int updateSlot = config.getSlotForButton("update");
        int toggleSlot = config.getSlotForButton("toggle");
        int deleteSlot = config.getSlotForButton("delete");
        int backSlot = config.getSlotForButton("back");

        if (slot == priceSlot) {
            openPriceEditGUI();
            return;
        }

        if (slot == updateSlot) {
            updateFromEditor();
            return;
        }

        if (slot == toggleSlot) {
            toggleAvailability();
            return;
        }

        if (slot == deleteSlot && isShiftClick) {
            openDeleteConfirm();
            return;
        }

        if (slot == backSlot) {
            player.closeInventory();
            parentGUI.refresh();
            parentGUI.open();
        }
    }

    private void openPriceEditGUI() {
        MusicEditGUI.startTextInput(
                player,
                null,
                "publish_price",
                input -> applyPriceInput(input),
                this::open
        );
        MessageUtils.send(player, Lang.PUBLISH_PRICE_EDIT_PROMPT, "{price}", String.format("%.0f", published.getPrice()));
    }

    private void applyPriceInput(String input) {
        try {
            double price = Double.parseDouble(input);
            double minPrice = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getPublishConfig().getMinPrice();
            double maxPrice = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getPublishConfig().getMaxPrice();

            if (!Double.isFinite(price) || price < minPrice || price > maxPrice) {
                MessageUtils.send(player, Lang.PUBLISH_PRICE_RANGE_ERROR.toString(
                        "{min}", String.format("%.0f", minPrice),
                        "{max}", String.format("%.0f", maxPrice)
                ));
                open();
                return;
            }

            boolean success = PublishedMusicManager.getInstance()
                    .updatePrice(published.getUniqueId(), price, player.getUniqueId());
            if (success) {
                MessageUtils.send(player, Lang.PUBLISH_PRICE_SET, "{price}", String.format("%.0f", price));
                refresh();
            } else {
                MessageUtils.send(player, Lang.OPERATION_FAILED);
            }
        } catch (NumberFormatException e) {
            MessageUtils.send(player, Lang.PUBLISH_PRICE_INVALID_NUMBER);
        }
        open();
    }

    private void updateFromEditor() {
        PlayerMusic music = PlayerMusicManager.getInstance().getMusicById(published.getOriginalMusicId());
        if (music == null) {
            MessageUtils.send(player, Lang.PUBLISH_ORIGINAL_NOT_FOUND);
            return;
        }

        boolean success = PublishedMusicManager.getInstance()
                .updatePublishedMusic(published.getUniqueId(), music, player.getUniqueId());
        
        if (success) {
            MessageUtils.send(player, Lang.PUBLISH_SYNC_SUCCESS);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
            updateInventory();
        } else {
            MessageUtils.send(player, Lang.PUBLISH_SYNC_FAILED);
        }
    }

    private void toggleAvailability() {
        boolean success;
        if (published.isAvailable()) {
            success = PublishedMusicManager.getInstance()
                    .unpublishMusic(published.getUniqueId(), player.getUniqueId());
            if (success) {
                MessageUtils.send(player, Lang.PUBLISH_UNPUBLISHED);
            }
        } else {
            success = PublishedMusicManager.getInstance()
                    .republishMusic(published.getUniqueId(), player.getUniqueId());
            if (success) {
                MessageUtils.send(player, Lang.PUBLISH_REPUBLISHED);
            }
        }
        
        if (success) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            updateInventory();
        } else {
            MessageUtils.send(player, Lang.PUBLISH_OPERATION_FAILED);
        }
    }

    private void openDeleteConfirm() {
        player.closeInventory();
        DeleteConfirmGUI confirmGUI = new DeleteConfirmGUI(
                player,
                published.getName(),
                this::deletePublished,
                this::open
        );
        confirmGUI.open();
    }

    private void deletePublished() {
        boolean success = PublishedMusicManager.getInstance().deletePublishedMusic(published.getUniqueId(), player.getUniqueId());
        if (!success) {
            MessageUtils.send(player, Lang.PUBLISH_OPERATION_FAILED);
            open();
            return;
        }
        MessageUtils.send(player, Lang.PUBLISH_DELETED);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f);
        player.closeInventory();
        parentGUI.refresh();
        parentGUI.open();
    }

    public PublishedMusic getPublished() {
        return published;
    }

    public void refresh() {
        updateInventory();
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
