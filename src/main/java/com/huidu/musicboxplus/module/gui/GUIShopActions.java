package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.EconomyUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

final class GUIShopActions {
    private GUIShopActions() {
    }

    static void playerBuyMusic(Logger logger, PlayerWrapper wrapper, MusicBoxSong musicBoxSong) {
        if (wrapper == null || musicBoxSong == null) {
            logger.warning("Invalid parameters for playerBuyMusic");
            return;
        }
        Player player = wrapper.getPlayer();
        if (player == null) {
            logger.warning("Player is null for wrapper");
            return;
        }
        double price = EconomyUtils.getDiscPrice();
        if (EconomyUtils.cannotBuy(player, price)) {
            return;
        }
        try {
            ItemStack songStack = musicBoxSong.getSongStack();
            if (songStack == null) {
                logger.severe("Song stack is null for music: " + musicBoxSong.getName());
                MessageUtils.send(player, Lang.ERROR_OCCURRED);
                return;
            }
            HashMap<Integer, ItemStack> result = player.getInventory().addItem(songStack);
            if (!result.isEmpty()) {
                MessageUtils.send(player, Lang.NO_INVENTORY_SPACE);
            } else if (EconomyUtils.buyNoMessage(player, price)) {
                MessageUtils.send(player, Lang.DISC_PURCHASED, "{disc}", musicBoxSong.getName());
            } else {
                player.getInventory().removeItem(songStack);
                MessageUtils.send(player, Lang.ERROR, "{message}", Lang.PAYMENT_FAILED.toString());
            }
        } catch (Exception e) {
            logger.severe("Failed to buy music: " + musicBoxSong.getName() + " for player: " + player.getName() + " - " + e.getMessage());
            MessageUtils.send(player, Lang.ERROR_OCCURRED);
        }
    }

    static List<String> playerBuySongLore(Logger logger, Map<String, List<String>> loreCache, GUIConfigManager.ShopLoreConfig shopLoreConfig, MusicBoxSong musicBoxSong) {
        double price = EconomyUtils.getDiscPrice();
        if (musicBoxSong == null) {
            logger.warning("Invalid music box song data provided");
            return replacePrice(shopLoreConfig.getShopLore(), price);
        }
        String cacheKey = "buy_lore_" + price;
        return loreCache.computeIfAbsent(cacheKey, k -> replacePrice(shopLoreConfig.getShopLore(), price));
    }

    static List<String> playerBuyAllContainerLore(Logger logger, Map<String, List<String>> loreCache, FullSongContainer container) {
        if (container == null) {
            logger.warning("Invalid container data provided");
            return new ArrayList<>();
        }
        try {
            int songCount = container.getAllSongCount();
            double totalPrice = EconomyUtils.getDiscPrice() * songCount;
            String cacheKey = "buy_container_lore_" + container.getNameId() + "_" + songCount + "_" + totalPrice;
            GUIConfigManager.ShopLoreConfig shopLoreConfig = GUIConfigManager.getInstance().getShopLoreConfig();
            return loreCache.computeIfAbsent(cacheKey, k -> replaceContainerPurchase(
                    shopLoreConfig.getContainerBuyAllLore(),
                    container,
                    songCount,
                    totalPrice
            ));
        } catch (Exception e) {
            logger.severe("Error calculating container price lore: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    static void openBuyAllContainerConfirm(Logger logger, PlayerWrapper playerWrapper, SongContainerGUI.SongGUIData<FullSongContainer> containerData) {
        if (playerWrapper == null || containerData == null || containerData.getData() == null) {
            return;
        }

        Player player = playerWrapper.getPlayer();
        if (player == null) {
            return;
        }

        FullSongContainer container = containerData.getData();
        int songCount = container.getAllSongCount();
        if (songCount <= 0) {
            MessageUtils.send(player, Lang.ERROR, "{message}", Lang.CONTAINER_HAS_NO_SONGS.toString());
            return;
        }

        GUIConfigManager.ShopContainerPurchaseConfirmConfig confirmConfig = GUIConfigManager.getInstance().getShopContainerPurchaseConfirmConfig();
        double totalPrice = EconomyUtils.getDiscPrice() * songCount;
        double balance = EconomyUtils.getBalance(player);
        boolean canAfford = balance >= totalPrice;
        String title = replaceContainerPurchase(confirmConfig.getTitle(), container, songCount, totalPrice, balance, Math.max(0D, totalPrice - balance));
        int rows = Math.max(1, Math.min(6, confirmConfig.getLayout().split("\n").length));
        GUI gui = new GUI(title, rows);

        addConfirmButton(gui, confirmConfig, "info", container, songCount, totalPrice, balance, canAfford);
        addConfirmButton(gui, confirmConfig, "price", container, songCount, totalPrice, balance, canAfford);

        String actionButton = canAfford ? "confirm" : "no-money";
        int actionSlot = confirmConfig.getSlotForButton(actionButton);
        if (actionSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig buttonConfig = confirmConfig.getButton(actionButton);
            if (buttonConfig != null) {
                ItemStack item = createConfirmItem(buttonConfig, container, songCount, totalPrice, balance, canAfford);
                gui.addItem(actionSlot, item, new ClickAction(() -> {
                    if (canAfford) {
                        buyAllContainer(logger, playerWrapper, container);
                    } else {
                        sendNoMoney(player, totalPrice, balance);
                    }
                }));
            }
        }

        int backSlot = confirmConfig.getSlotForButton("back");
        if (backSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig backConfig = confirmConfig.getButton("back");
            if (backConfig != null) {
                gui.addItem(backSlot, createConfirmItem(backConfig, container, songCount, totalPrice, balance, canAfford), new ClickAction(() -> {
                    if (containerData.getGui() != null) {
                        containerData.getGui().openPage(containerData.getPage(), containerData.getParams(), containerData.getGuiType());
                    } else {
                        GUIActions.openShopInventory(playerWrapper);
                    }
                }));
            }
        }

        gui.open(player);
    }

    static void buyAllContainer(Logger logger, PlayerWrapper playerWrapper, FullSongContainer container) {
        if (playerWrapper == null || container == null) {
            return;
        }

        Player player = playerWrapper.getPlayer();
        if (player == null) {
            return;
        }

        List<MusicBoxSong> songs = container.getAllSongs();
        if (songs == null || songs.isEmpty()) {
            MessageUtils.send(player, Lang.ERROR, "{message}", Lang.CONTAINER_HAS_NO_SONGS.toString());
            return;
        }

        int songCount = songs.size();
        double pricePerDisc = EconomyUtils.getDiscPrice();
        double totalPrice = pricePerDisc * songCount;

        int emptySlots = countEmptySlots(player);
        if (emptySlots < songCount) {
            MessageUtils.send(player, Lang.BUY_CONTAINER_NO_SPACE,
                "{need}", String.valueOf(songCount),
                "{have}", String.valueOf(emptySlots)
            );
            return;
        }

        if (!EconomyUtils.hasMoney(player, totalPrice)) {
            sendNoMoney(player, totalPrice, EconomyUtils.getBalance(player));
            return;
        }

        if (!EconomyUtils.buyNoMessage(player, totalPrice)) {
            MessageUtils.send(player, Lang.ERROR, "{message}", Lang.PAYMENT_FAILED.toString());
            return;
        }

        int successCount = 0;
        for (MusicBoxSong song : songs) {
            try {
                ItemStack songStack = song.getSongStack();
                if (songStack == null) {
                    continue;
                }
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(songStack);
                // The player already paid for every disc up front, so anything that
                // didn't fit must not be discarded — drop it at their feet instead.
                for (ItemStack overflow : leftover.values()) {
                    player.getWorld().dropItem(player.getLocation(), overflow);
                }
                successCount++;
            } catch (Exception e) {
                logger.warning("Failed to give disc " + song.getName() + " - " + e.getMessage());
            }
        }

        MessageUtils.send(player, Lang.BUY_CONTAINER_SUCCESS,
            "{count}", String.valueOf(successCount),
            "{price}", String.format("%.0f", totalPrice)
        );
    }

    private static List<String> replacePrice(List<String> lore, double price) {
        List<String> result = new ArrayList<>();
        for (String line : lore) {
            result.add(line.replace("{price}", String.format("%.0f", price)));
        }
        return result;
    }

    private static void addConfirmButton(GUI gui, GUIConfigManager.ShopContainerPurchaseConfirmConfig confirmConfig, String buttonName,
            FullSongContainer container, int songCount, double totalPrice, double balance, boolean canAfford) {
        int slot = confirmConfig.getSlotForButton(buttonName);
        if (slot < 0) {
            return;
        }
        GUIConfigManager.HotbarButtonConfig buttonConfig = confirmConfig.getButton(buttonName);
        if (buttonConfig == null) {
            return;
        }
        gui.addItem(slot, createConfirmItem(buttonConfig, container, songCount, totalPrice, balance, canAfford), new ClickAction(() -> {}));
    }

    private static ItemStack createConfirmItem(GUIConfigManager.HotbarButtonConfig config, FullSongContainer container,
            int songCount, double totalPrice, double balance, boolean canAfford) {
        double need = Math.max(0D, totalPrice - balance);
        List<String> lore = replaceContainerPurchase(config.getLore(), container, songCount, totalPrice, balance, need);
        String name = replaceContainerPurchase(config.getName(), container, songCount, totalPrice, balance, need);
        return ItemUtils.createStack(config.getMaterial(), name, lore, config.getCustomModelData());
    }

    private static List<String> replaceContainerPurchase(List<String> lore, FullSongContainer container, int songCount, double totalPrice) {
        return replaceContainerPurchase(lore, container, songCount, totalPrice, 0D, totalPrice);
    }

    private static List<String> replaceContainerPurchase(List<String> lore, FullSongContainer container,
            int songCount, double totalPrice, double balance, double need) {
        List<String> result = new ArrayList<>();
        for (String line : lore) {
            result.add(replaceContainerPurchase(line, container, songCount, totalPrice, balance, need));
        }
        return StringUtils.t(result);
    }

    private static String replaceContainerPurchase(String text, FullSongContainer container,
            int songCount, double totalPrice, double balance, double need) {
        return text
                .replace("{folder}", container.getName())
                .replace("{name}", container.getName())
                .replace("{count}", String.valueOf(songCount))
                .replace("{price}", formatMoney(totalPrice))
                .replace("{balance}", formatMoney(balance))
                .replace("{need}", formatMoney(need));
    }

    private static void sendNoMoney(Player player, double totalPrice, double balance) {
        MessageUtils.send(player, Lang.BUY_CONTAINER_NO_MONEY,
                "{price}", formatMoney(totalPrice),
                "{balance}", formatMoney(balance),
                "{need}", formatMoney(totalPrice - balance)
        );
    }

    private static String formatMoney(double value) {
        return String.format("%.0f", value);
    }

    private static int countEmptySlots(Player player) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                count++;
            }
        }
        return count;
    }
}
