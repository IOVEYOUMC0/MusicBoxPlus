package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.EconomyUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

public class PurchaseConfirmGUI implements InventoryHolder {

    private final Player player;
    private final PublishedMusic published;
    private final PlayerMusicShopGUI parentGUI;
    private final Inventory inventory;
    private final GUIConfigManager.PurchaseConfirmConfig config;
    // Guards against double-clicking "confirm": money is withdrawn synchronously but
    // the disc/save completes asynchronously, leaving a window in which a second click
    // would withdraw again. True while a purchase is in flight.
    private double displayedPrice = -1D;
    private boolean purchasing = false;

    public PurchaseConfirmGUI(Player player, PublishedMusic published, PlayerMusicShopGUI parentGUI) {
        this.player = player;
        this.published = published;
        this.parentGUI = parentGUI;
        this.config = GUIConfigManager.getInstance().getPurchaseConfirmConfig();
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
                        .replace("{timeSignature}", published.getTimeSignature().toString())
                        .replace("{sales}", String.valueOf(published.getSalesCount()))
                        .replace("{publishedAt}", formatDate(published.getPublishedAt())));
            }
            String name = infoConfig.getName().replace("{name}", published.getName());
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), name, lore, infoConfig.getCustomModelData());
            inventory.setItem(infoSlot, infoItem);
        }

        boolean authorClaim = PublishedMusicManager.getInstance().canClaimOwnMusic(published, player);
        double price = authorClaim ? 0D : published.getPrice();
        // What the buyer is actually looking at. published is the live cached object the author's
        // manage GUI mutates, so confirm has to check the price has not moved underneath them.
        this.displayedPrice = price;
        double balance = EconomyUtils.getBalance(player);
        boolean canAfford = authorClaim || balance >= price;

        int priceSlot = config.getSlotForButton("price");
        if (priceSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig priceConfig = config.getButton("price");
            String priceColor = canAfford ? "<gold>" : "<red>";
            String balanceColor = canAfford ? "<green>" : "<red>";
            String canAffordText = authorClaim
                    ? Lang.SHOP_MUSIC_ACTION_OWN_CLAIM.toString()
                    : canAfford ? "<green>Can afford" : "<red>Insufficient funds";

            List<String> lore = new ArrayList<>();
            for (String line : priceConfig.getLore()) {
                lore.add(line.replace("{price}", String.format("%.0f", price))
                        .replace("{balance}", String.format("%.2f", balance))
                        .replace("{originalPrice}", String.format("%.0f", published.getPrice()))
                        .replace("{priceColor}", priceColor)
                        .replace("{balanceColor}", balanceColor)
                        .replace("{canAfford}", canAffordText));
            }
            String name = priceConfig.getName().replace("{price}", String.format("%.0f", price))
                    .replace("{originalPrice}", String.format("%.0f", published.getPrice()))
                    .replace("{priceColor}", priceColor);
            ItemStack priceItem = ItemUtils.createStack(priceConfig.getMaterial(), name, lore, priceConfig.getCustomModelData());
            inventory.setItem(priceSlot, priceItem);
        }

        int confirmSlot = config.getSlotForButton("confirm");
        if (confirmSlot >= 0) {
            if (canAfford) {
                GUIConfigManager.HotbarButtonConfig confirmConfig = config.getButton("confirm");
                ItemStack confirmItem = confirmConfig.createItem();
                inventory.setItem(confirmSlot, confirmItem);
            } else {
                GUIConfigManager.HotbarButtonConfig noMoneyConfig = config.getButton("no-money");
                if (noMoneyConfig != null) {
                    double need = Math.max(0D, price - balance);
                    List<String> lore = new ArrayList<>();
                    for (String line : noMoneyConfig.getLore()) {
                        lore.add(line.replace("{need}", String.format("%.0f", need)));
                    }
                    ItemStack noMoneyItem = ItemUtils.createStack(noMoneyConfig.getMaterial(), noMoneyConfig.getName(), lore, noMoneyConfig.getCustomModelData());
                    inventory.setItem(confirmSlot, noMoneyItem);
                }
            }
        }

        int backSlot = config.getSlotForButton("back");
        if (backSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig backConfig = config.getButton("back");
            ItemStack backButton = backConfig.createItem();
            inventory.setItem(backSlot, backButton);
        }
    }

    private String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new Date(timestamp));
    }

    public void handleClick(int slot) {
        int confirmSlot = config.getSlotForButton("confirm");
        int backSlot = config.getSlotForButton("back");

        if (slot == backSlot) {
            player.closeInventory();
            parentGUI.open();
            return;
        }

        if (slot == confirmSlot) {
            purchaseMusic();
        }
    }

    private void purchaseMusic() {
        // Re-entrancy guard: a rapid second confirm-click must not trigger a second
        // withdrawal while the first purchase is still saving asynchronously.
        if (purchasing) {
            return;
        }
        purchasing = true;
        boolean asyncInProgress = false;
        try {
            double paidPrice = published.getPrice();
            boolean authorClaim = PublishedMusicManager.getInstance().canClaimOwnMusic(published, player);
            double currentPrice = authorClaim ? 0D : paidPrice;
            if (currentPrice != displayedPrice) {
                // Re-rendered instead of charged: the buyer confirms the new number themselves.
                purchasing = false;
                MessageUtils.send(player, Lang.OPERATION_FAILED);
                updateInventory();
                return;
            }
            PublishedMusicManager.PurchaseResult result = PublishedMusicManager.getInstance()
                    .purchaseMusic(published.getUniqueId(), player, paidPrice);
            switch (result) {
                case SUCCESS:
                    asyncInProgress = true;
                    savePurchasedMusic(purchasedMusic -> {
                        purchasing = false;
                        if (!player.isOnline()) {
                            // Refund rather than deliver: addItem on a disconnected Player writes
                            // into an inventory that is never saved, and giveDisc would still
                            // report success, so the buyer would be charged for nothing.
                            EconomyUtils.depositPlayer(player, paidPrice);
                            PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                            return;
                        }
                        if (PublishedMusicManager.getInstance().recordSuccessfulPurchase(published.getUniqueId(), player, paidPrice)) {
                            if (PlayerMusicDiscHelper.giveDisc(player, purchasedMusic, published)) {
                                PublishedMusicManager.getInstance().rollbackRecordedPurchase(published.getUniqueId(), paidPrice);
                                EconomyUtils.depositPlayer(player, paidPrice);
                                PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                                MessageUtils.send(player, Lang.OPERATION_FAILED);
                                return;
                            }
                            completePurchase(Lang.PURCHASE_SUCCESS_MSG);
                        } else {
                            EconomyUtils.depositPlayer(player, paidPrice);
                            PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                            MessageUtils.send(player, Lang.PURCHASE_PAYMENT_FAILED);
                        }
                    }, () -> {
                        purchasing = false;
                        EconomyUtils.depositPlayer(player, paidPrice);
                        MessageUtils.send(player, Lang.PURCHASE_SAVE_FAILED);
                    });
                    break;
                case AUTHOR_CLAIM:
                    PlayerMusic originalMusic = getOriginalOwnMusic();
                    if (originalMusic != null) {
                        claimExistingOwnMusic(originalMusic);
                        break;
                    }
                    asyncInProgress = true;
                    savePurchasedMusic(purchasedMusic -> {
                        purchasing = false;
                        if (PublishedMusicManager.getInstance().recordAuthorClaim(published.getUniqueId(), player)) {
                            if (PlayerMusicDiscHelper.giveDisc(player, purchasedMusic, published)) {
                                PublishedMusicManager.getInstance().rollbackAuthorClaim(published.getUniqueId(), player);
                                PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                                MessageUtils.send(player, Lang.OPERATION_FAILED);
                                return;
                            }
                            completePurchase(Lang.PURCHASE_AUTHOR_CLAIM_SUCCESS);
                        } else {
                            PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                            MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
                        }
                    }, () -> {
                        purchasing = false;
                        MessageUtils.send(player, Lang.PURCHASE_SAVE_FAILED);
                    });
                    break;
                case NOT_FOUND:
                    MessageUtils.send(player, Lang.PURCHASE_MUSIC_NOT_FOUND);
                    break;
                case NOT_AVAILABLE:
                    MessageUtils.send(player, Lang.PURCHASE_MUSIC_UNAVAILABLE);
                    break;
                case OWN_MUSIC:
                    if (giveAlreadyClaimedOwnMusic()) {
                        MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
                    }
                    break;
                case NO_MONEY:
                    MessageUtils.send(player, Lang.PURCHASE_NO_MONEY);
                    break;
                case PAYMENT_FAILED:
                    MessageUtils.send(player, Lang.PURCHASE_PAYMENT_FAILED);
                    break;
            }
        } finally {
            // Synchronous paths end the in-flight window immediately; async paths clear
            // the flag inside their callbacks once the save resolves.
            if (!asyncInProgress) {
                purchasing = false;
            }
        }
    }

    private PlayerMusic getOriginalOwnMusic() {
        PlayerMusic originalMusic = PlayerMusicManager.getInstance().getMusicById(published.getOriginalMusicId());
        if (originalMusic == null || !player.getUniqueId().equals(originalMusic.getAuthorUUID())) {
            return null;
        }
        return originalMusic;
    }

    private void claimExistingOwnMusic(PlayerMusic originalMusic) {
        if (PublishedMusicManager.getInstance().recordAuthorClaim(published.getUniqueId(), player)) {
            if (PlayerMusicDiscHelper.giveDisc(player, originalMusic, published)) {
                PublishedMusicManager.getInstance().rollbackAuthorClaim(published.getUniqueId(), player);
                MessageUtils.send(player, Lang.OPERATION_FAILED);
                return;
            }
            completePurchase(Lang.PURCHASE_AUTHOR_CLAIM_SUCCESS);
        } else {
            if (giveAlreadyClaimedOwnMusic()) {
                MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
            }
        }
    }

    // Reaching here means the author already claimed this song and authorClaimOwnMusicOnce is
    // on. Handing out another disc made that setting mean nothing; the once flag is the answer.
    private boolean giveAlreadyClaimedOwnMusic() {
        MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
        player.closeInventory();
        return false;
    }

    private void savePurchasedMusic(Consumer<PlayerMusic> onSuccess, Runnable onFailure) {
        PlayerMusic purchasedMusic = published.toPlayerMusic(player.getName(), player.getUniqueId());
        PlayerMusicManager.getInstance().saveMusicAsync(purchasedMusic, success -> {
            if (!success) {
                onFailure.run();
                return;
            }
            onSuccess.accept(purchasedMusic);
        });
    }

    private void completePurchase(Lang message) {
        MessageUtils.send(player, message, "{name}", published.getName());
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
        player.closeInventory();
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
