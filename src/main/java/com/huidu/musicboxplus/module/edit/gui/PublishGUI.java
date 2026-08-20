package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.SoundUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PublishGUI implements InventoryHolder {

    private final Player player;
    private final Inventory inventory;
    private final List<PlayerMusic> musicList;
    private final GUIConfigManager.PublishGUIConfig config;
    private int page = 0;
    private final Map<Integer, PlayerMusic> slotMusicMap = new HashMap<>();

    public PublishGUI(Player player) {
        this.player = player;
        this.config = GUIConfigManager.getInstance().getPublishGUIConfig();
        this.musicList = PlayerMusicManager.getInstance().getMusicByPlayer(player);
        String title = config.getTitle();
        this.inventory = Bukkit.createInventory(this, 54, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();
        slotMusicMap.clear();

        List<Integer> musicSlots = config.getSlotsForChar(config.getButtonMapping().getOrDefault("music-item", 'M'));
        int itemsPerPage = musicSlots.size();
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, musicList.size());

        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= musicSlots.size()) break;
            
            int slot = musicSlots.get(slotIndex);
            PlayerMusic music = musicList.get(i);
            
            PublishedMusic publishedMusic = findPublishedMusic(music);
            boolean hasPublishedListing = publishedMusic != null;
            boolean isPublished = publishedMusic != null && publishedMusic.isAvailable();
            
            GUIConfigManager.HotbarButtonConfig musicConfig = config.getButton("music-item");
            Material material = musicConfig.getMaterial();
            if (hasPublishedListing) {
                GUIConfigManager.HotbarButtonConfig publishedConfig = config.getButton("music-item-published");
                if (publishedConfig != null && publishedConfig.getMaterial() != null) {
                    material = publishedConfig.getMaterial();
                }
            }
            
            String status;
            if (publishedMusic != null && publishedMusic.isPending()) {
                status = Lang.PUBLISH_STATUS_PENDING.toString();
            } else if (publishedMusic != null && !publishedMusic.isApproved()) {
                status = Lang.PUBLISH_STATUS_REJECTED.toString();
            } else {
                status = isPublished ? Lang.PUBLISH_STATUS_PUBLISHED.toString() : Lang.PUBLISH_STATUS_UNPUBLISHED.toString();
            }
            String action = hasPublishedListing ? Lang.PUBLISH_ACTION_MANAGE.toString() : Lang.PUBLISH_ACTION_PUBLISH.toString();
            
            List<String> lore = new ArrayList<>();
            for (String line : musicConfig.getLore()) {
                lore.add(line.replace("{notes}", String.valueOf(music.getNoteCount()))
                        .replace("{bpm}", String.valueOf(music.getBpm()))
                        .replace("{status}", status)
                        .replace("{action}", action));
            }
            
            String name = musicConfig.getName().replace("{name}", music.getName());
            ItemStack item = ItemUtils.createStack(material, name, lore, musicConfig.getCustomModelData());
            
            if (isPublished) {
                GUIConfigManager.HotbarButtonConfig publishedConfig = config.getButton("music-item-published");
                if (publishedConfig != null && publishedConfig.isGlow()) {
                    item = ItemUtils.glow(item);
                }
            }
            
            inventory.setItem(slot, item);
            slotMusicMap.put(slot, music);
        }

        int prevSlot = config.getSlotForButton("prev-page");
        if (prevSlot >= 0 && page > 0) {
            GUIConfigManager.HotbarButtonConfig prevConfig = config.getButton("prev-page");
            List<String> lore = new ArrayList<>();
            int totalPages = Math.max(1, (int) Math.ceil((double) musicList.size() / itemsPerPage));
            for (String line : prevConfig.getLore()) {
                lore.add(line.replace("{page}", String.valueOf(page + 1))
                        .replace("{totalPages}", String.valueOf(totalPages)));
            }
            ItemStack prevButton = ItemUtils.createStack(prevConfig.getMaterial(), prevConfig.getName(), lore, prevConfig.getCustomModelData());
            inventory.setItem(prevSlot, prevButton);
        }

        int nextSlot = config.getSlotForButton("next-page");
        if (nextSlot >= 0 && (page + 1) * itemsPerPage < musicList.size()) {
            GUIConfigManager.HotbarButtonConfig nextConfig = config.getButton("next-page");
            List<String> lore = new ArrayList<>();
            int totalPages = Math.max(1, (int) Math.ceil((double) musicList.size() / itemsPerPage));
            for (String line : nextConfig.getLore()) {
                lore.add(line.replace("{page}", String.valueOf(page + 1))
                        .replace("{totalPages}", String.valueOf(totalPages)));
            }
            ItemStack nextButton = ItemUtils.createStack(nextConfig.getMaterial(), nextConfig.getName(), lore, nextConfig.getCustomModelData());
            inventory.setItem(nextSlot, nextButton);
        }

        int closeSlot = config.getSlotForButton("close");
        if (closeSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig closeConfig = config.getButton("close");
            ItemStack closeButton = closeConfig.createItem();
            inventory.setItem(closeSlot, closeButton);
        }

        double pendingRevenue = PublishedMusicManager.getInstance().getPendingRevenue(player.getUniqueId());
        int revenueSlot = config.getSlotForButton("revenue");
        if (revenueSlot >= 0 && pendingRevenue > 0) {
            GUIConfigManager.HotbarButtonConfig revenueConfig = config.getButton("revenue");
            List<String> lore = new ArrayList<>();
            for (String line : revenueConfig.getLore()) {
                lore.add(line.replace("{amount}", String.format("%.2f", pendingRevenue)));
            }
            String name = revenueConfig.getName().replace("{amount}", String.format("%.2f", pendingRevenue));
            ItemStack revenueButton = ItemUtils.createStack(revenueConfig.getMaterial(), name, lore, revenueConfig.getCustomModelData());
            inventory.setItem(revenueSlot, revenueButton);
        }
    }

    private PublishedMusic findPublishedMusic(PlayerMusic music) {
        List<PublishedMusic> published = PublishedMusicManager.getInstance().getPublishedByAuthor(player.getUniqueId());
        return published.stream()
                .filter(p -> music.getUniqueId().equals(p.getOriginalMusicId()))
                .findFirst()
                .orElse(null);
    }

    public void handleClick(int slot) {
        List<Integer> musicSlots = config.getSlotsForChar(config.getButtonMapping().getOrDefault("music-item", 'M'));
        int itemsPerPage = musicSlots.size();
        int prevSlot = config.getSlotForButton("prev-page");
        int nextSlot = config.getSlotForButton("next-page");
        int closeSlot = config.getSlotForButton("close");
        int revenueSlot = config.getSlotForButton("revenue");

        if (slot == prevSlot && page > 0) {
            page--;
            updateInventory();
            SoundUtils.playClickSound(player);
            return;
        }

        if (slot == nextSlot && (page + 1) * itemsPerPage < musicList.size()) {
            page++;
            updateInventory();
            SoundUtils.playClickSound(player);
            return;
        }

        if (slot == closeSlot) {
            player.closeInventory();
            return;
        }

        if (slot == revenueSlot) {
            double claimed = PublishedMusicManager.getInstance().claimRevenue(player);
            if (claimed > 0) {
                MessageUtils.send(player, Lang.REVENUE_CLAIMED_MSG, "{amount}", String.format("%.2f", claimed));
                SoundUtils.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
            }
            updateInventory();
            return;
        }

        PlayerMusic music = slotMusicMap.get(slot);
        if (music != null) {
            PublishedMusic published = findPublishedMusic(music);
            if (published != null) {
                openManageGUI(published);
            } else {
                openPublishConfirmGUI(music);
            }
        }
    }

    private void openPublishConfirmGUI(PlayerMusic music) {
        player.closeInventory();
        PublishConfirmGUI confirmGUI = new PublishConfirmGUI(player, music, this);
        confirmGUI.open();
    }

    private void openManageGUI(PublishedMusic published) {
        player.closeInventory();
        ManagePublishedGUI manageGUI = new ManagePublishedGUI(player, published, this);
        manageGUI.open();
    }

    // On the target's own scheduler: an admin can open this for someone else, from another region.
    public void open() {
        Scheduler.entity(player, () -> player.openInventory(inventory));
    }

    public void refresh() {
        updateInventory();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
