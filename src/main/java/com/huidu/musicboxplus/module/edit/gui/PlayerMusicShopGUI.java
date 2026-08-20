package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.MusicEditGUI;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicDiscHelper;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerMusicShopGUI implements InventoryHolder {

    private static final Map<UUID, PlayerMusicShopGUI> openGUIs = new ConcurrentHashMap<>();

    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.PlayerShopConfig config;
    private List<PublishedMusic> musicList;
    private final Map<Integer, PublishedMusic> slotMusicMap = new HashMap<>();
    private int page = 0;
    private String searchQuery = null;

    public PlayerMusicShopGUI(Player player) {
        this.player = player;
        this.config = GUIConfigManager.getInstance().getPlayerShopConfig();
        String title = config.getTitle();
        this.inventory = Bukkit.createInventory(this, 54, MiniMessageUtils.processComponent(title));
        loadMusicList();
        updateInventory();
    }

    private void loadMusicList() {
        // Both paths return listings already sorted by sales (desc) from the manager's
        // cached view, so no per-open filter+sort over the whole catalog here.
        if (searchQuery != null && !searchQuery.isEmpty()) {
            musicList = PublishedMusicManager.getInstance().searchPublished(searchQuery);
        } else {
            musicList = PublishedMusicManager.getInstance().getAvailableSortedBySales();
        }
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
            PublishedMusic music = musicList.get(i);
            
            GUIConfigManager.HotbarButtonConfig musicConfig = config.getButton("music-item");
            
            String descLine = music.getDescription() != null && !music.getDescription().isEmpty() 
                    ? Lang.SHOP_MUSIC_DESCRIPTION_PREFIX.toString("{description}", music.getDescription()) : "";
            boolean ownMusic = music.getAuthorUUID().equals(player.getUniqueId());
            boolean canClaimOwn = ownMusic && PublishedMusicManager.getInstance().canClaimOwnMusic(music, player);
            double displayPrice = canClaimOwn ? 0D : music.getPrice();
            String action = ownMusic
                    ? (canClaimOwn ? Lang.SHOP_MUSIC_ACTION_OWN_CLAIM.toString() : Lang.SHOP_MUSIC_ACTION_OWN.toString())
                    : Lang.SHOP_MUSIC_ACTION_BUY.toString();
            
            List<String> lore = new ArrayList<>();
            for (String line : musicConfig.getLore()) {
                lore.add(line.replace("{name}", music.getName())
                        .replace("{author}", music.getAuthor())
                        .replace("{notes}", String.valueOf(music.getNoteCount()))
                        .replace("{bpm}", String.valueOf(music.getBpm()))
                        .replace("{description}", descLine)
                        .replace("{price}", String.format("%.0f", displayPrice))
                        .replace("{originalPrice}", String.format("%.0f", music.getPrice()))
                        .replace("{sales}", String.valueOf(music.getSalesCount()))
                        .replace("{action}", action));
            }
            
            String name = musicConfig.getName().replace("{name}", music.getName());
            ItemStack item = ItemUtils.createStack(musicConfig.getMaterial(), name, lore, musicConfig.getCustomModelData());
            inventory.setItem(slot, item);
            slotMusicMap.put(slot, music);
        }

        int prevSlot = config.getSlotForButton("prev-page");
        if (prevSlot >= 0 && page > 0) {
            GUIConfigManager.HotbarButtonConfig prevConfig = config.getButton("prev-page");
            int totalPages = Math.max(1, (int) Math.ceil((double) musicList.size() / itemsPerPage));
            List<String> lore = new ArrayList<>();
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
            int totalPages = Math.max(1, (int) Math.ceil((double) musicList.size() / itemsPerPage));
            List<String> lore = new ArrayList<>();
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

        int searchSlot = config.getSlotForButton("search");
        if (searchSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig searchConfig = config.getButton("search");
            String queryDisplay = searchQuery == null ? Lang.SHOP_SEARCH_ALL.toString() : "<yellow>" + searchQuery + "</yellow>";
            List<String> lore = new ArrayList<>();
            for (String line : searchConfig.getLore()) {
                lore.add(line.replace("{query}", queryDisplay));
            }
            ItemStack searchButton = ItemUtils.createStack(searchConfig.getMaterial(), searchConfig.getName(), lore, searchConfig.getCustomModelData());
            inventory.setItem(searchSlot, searchButton);
        }

        int myMusicSlot = config.getSlotForButton("my-music");
        if (myMusicSlot >= 0 && MusicBox.getInstance().isPublishModuleEnabled()) {
            GUIConfigManager.HotbarButtonConfig myMusicConfig = config.getButton("my-music");
            ItemStack myMusicButton = myMusicConfig.createItem();
            inventory.setItem(myMusicSlot, myMusicButton);
        }
    }

    public void handleClick(int slot, boolean isShiftClick) {
        List<Integer> musicSlots = config.getSlotsForChar(config.getButtonMapping().getOrDefault("music-item", 'M'));
        int itemsPerPage = musicSlots.size();
        int prevSlot = config.getSlotForButton("prev-page");
        int nextSlot = config.getSlotForButton("next-page");
        int closeSlot = config.getSlotForButton("close");
        int searchSlot = config.getSlotForButton("search");
        int myMusicSlot = config.getSlotForButton("my-music");

        if (slot == prevSlot && page > 0) {
            page--;
            updateInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        if (slot == nextSlot && (page + 1) * itemsPerPage < musicList.size()) {
            page++;
            updateInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        if (slot == closeSlot) {
            player.closeInventory();
            return;
        }

        if (slot == searchSlot) {
            player.closeInventory();
            MessageUtils.send(player, Lang.EDIT_INPUT_SEARCH_PROMPT);
            startSearchInput();
            return;
        }

        if (slot == myMusicSlot && MusicBox.getInstance().isPublishModuleEnabled()) {
            player.closeInventory();
            PublishGUI publishGUI = new PublishGUI(player);
            publishGUI.open();
            return;
        }

        PublishedMusic music = slotMusicMap.get(slot);
        if (music != null) {
            if (music.getAuthorUUID().equals(player.getUniqueId())
                    && !PublishedMusicManager.getInstance().canClaimOwnMusic(music, player)) {
                // No re-give here. canClaimOwnMusic is false precisely because the author already
                // claimed this song and authorClaimOwnMusicOnce is on; handing out another disc
                // made that setting mean nothing, and the discs are transferable.
                MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
                return;
            }
            openPurchaseConfirmGUI(music);
        }
    }

    private void openPurchaseConfirmGUI(PublishedMusic music) {
        player.closeInventory();
        PurchaseConfirmGUI confirmGUI = new PurchaseConfirmGUI(player, music, this);
        confirmGUI.open();
    }

    private void startSearchInput() {
        MusicEditGUI.startTextInput(player, null, "shop_search");
    }

    public void setSearchQuery(String query) {
        if (query.equalsIgnoreCase("all")) {
            this.searchQuery = null;
        } else {
            this.searchQuery = query;
        }
        this.page = 0;
        loadMusicList();
        updateInventory();
    }

    // On the target's own scheduler: an admin can open this for someone else, from another region.
    public void open() {
        Scheduler.entity(player, () -> {
            openGUIs.put(player.getUniqueId(), this);
            player.openInventory(inventory);
        });
    }

    public void refresh() {
        loadMusicList();
        updateInventory();
    }

    public static PlayerMusicShopGUI getOpenGUI(UUID playerUUID) {
        return openGUIs.get(playerUUID);
    }

    public static void removeOpenGUI(UUID playerUUID) {
        openGUIs.remove(playerUUID);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
