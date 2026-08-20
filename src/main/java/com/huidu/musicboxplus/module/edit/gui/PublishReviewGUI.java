package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Admin review queue for the publish approval flow: lists every pending listing, left-click
// approves it, right-click rejects it. The queue is re-fetched on every render, so decisions
// made elsewhere (or by other admins) show up on the next refresh/page turn.
public class PublishReviewGUI implements InventoryHolder {

    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.PublishReviewConfig config;
    // The layout char for listing slots comes from the button-mapping, matching how the
    // other config-driven GUIs resolve their item chars (default 'R').
    private final char reviewChar;
    private List<PublishedMusic> pendingList;
    private final Map<Integer, PublishedMusic> slotMusicMap = new HashMap<>();
    private int page = 0;

    public PublishReviewGUI(Player player) {
        this.player = player;
        this.config = GUIConfigManager.getInstance().getPublishReviewConfig();
        this.reviewChar = config.getButtonMapping().getOrDefault("review-item", 'R');
        String title = config.getTitle();
        this.inventory = Bukkit.createInventory(this, 54, MiniMessageUtils.processComponent(title));
        loadPendingList();
        updateInventory();
    }

    private void loadPendingList() {
        pendingList = PublishedMusicManager.getInstance().getPendingPublished();
    }

    private void updateInventory() {
        inventory.clear();
        slotMusicMap.clear();

        List<Integer> reviewSlots = config.getSlotsForChar(reviewChar);
        int itemsPerPage = reviewSlots.size();
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, pendingList.size());

        GUIConfigManager.HotbarButtonConfig reviewConfig = config.getButton("review-item");
        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= reviewSlots.size()) {
                break;
            }
            int slot = reviewSlots.get(slotIndex);
            PublishedMusic music = pendingList.get(i);

            List<String> lore = new ArrayList<>();
            for (String line : reviewConfig.getLore()) {
                lore.add(line.replace("{name}", music.getName())
                        .replace("{author}", music.getAuthor())
                        .replace("{price}", String.format("%.0f", music.getPrice()))
                        .replace("{notes}", String.valueOf(music.getNoteCount())));
            }
            String name = reviewConfig.getName().replace("{name}", music.getName());
            ItemStack item = ItemUtils.createStack(reviewConfig.getMaterial(), name, lore, reviewConfig.getCustomModelData());
            inventory.setItem(slot, item);
            slotMusicMap.put(slot, music);
        }

        int prevSlot = config.getSlotForButton("prev-page");
        if (prevSlot >= 0 && page > 0) {
            inventory.setItem(prevSlot, pageButton("prev-page"));
        }

        int nextSlot = config.getSlotForButton("next-page");
        if (nextSlot >= 0 && (page + 1) * itemsPerPage < pendingList.size()) {
            inventory.setItem(nextSlot, pageButton("next-page"));
        }

        int closeSlot = config.getSlotForButton("close");
        if (closeSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig closeConfig = config.getButton("close");
            inventory.setItem(closeSlot, closeConfig.createItem());
        }

        int refreshSlot = config.getSlotForButton("refresh");
        if (refreshSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig refreshConfig = config.getButton("refresh");
            inventory.setItem(refreshSlot, refreshConfig.createItem());
        }
    }

    private ItemStack pageButton(String key) {
        GUIConfigManager.HotbarButtonConfig buttonConfig = config.getButton(key);
        int itemsPerPage = config.getSlotsForChar(reviewChar).size();
        int totalPages = Math.max(1, (int) Math.ceil((double) pendingList.size() / itemsPerPage));
        List<String> lore = new ArrayList<>();
        for (String line : buttonConfig.getLore()) {
            lore.add(line.replace("{page}", String.valueOf(page + 1))
                    .replace("{totalPages}", String.valueOf(totalPages)));
        }
        return ItemUtils.createStack(buttonConfig.getMaterial(), buttonConfig.getName(), lore, buttonConfig.getCustomModelData());
    }

    public void handleClick(int slot, boolean isRightClick) {
        int prevSlot = config.getSlotForButton("prev-page");
        int nextSlot = config.getSlotForButton("next-page");
        int closeSlot = config.getSlotForButton("close");
        int refreshSlot = config.getSlotForButton("refresh");

        if (slot == prevSlot && page > 0) {
            page--;
            updateInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        if (slot == nextSlot && (page + 1) * config.getSlotsForChar(reviewChar).size() < pendingList.size()) {
            page++;
            updateInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        if (slot == closeSlot) {
            player.closeInventory();
            return;
        }

        if (slot == refreshSlot) {
            loadPendingList();
            page = 0;
            updateInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        PublishedMusic music = slotMusicMap.get(slot);
        if (music == null) {
            return;
        }
        if (isRightClick) {
            decide(music, false);
        } else {
            decide(music, true);
        }
    }

    private void decide(PublishedMusic music, boolean approve) {
        boolean success = approve
                ? PublishedMusicManager.getInstance().approveMusic(music.getUniqueId())
                : PublishedMusicManager.getInstance().rejectMusic(music.getUniqueId());
        if (!success) {
            MessageUtils.send(player, Lang.OPERATION_FAILED);
            return;
        }
        MessageUtils.send(player, approve ? Lang.PUBLISH_REVIEW_APPROVED : Lang.PUBLISH_REVIEW_REJECTED,
                "{name}", music.getName());
        player.playSound(player.getLocation(), approve
                ? Sound.ENTITY_PLAYER_LEVELUP
                : Sound.ENTITY_ITEM_BREAK, 0.5f, 1.0f);
        loadPendingList();
        int itemsPerPage = config.getSlotsForChar(reviewChar).size();
        if (pendingList.isEmpty()) {
            page = 0;
        } else if (page * itemsPerPage >= pendingList.size()) {
            page = (pendingList.size() - 1) / itemsPerPage;
        }
        updateInventory();
    }

    public void open() {
        // Open on the player's own region: commands run on the global region (Folia), clicks on
        // the entity's — entityNow runs inline when already on the right thread.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
