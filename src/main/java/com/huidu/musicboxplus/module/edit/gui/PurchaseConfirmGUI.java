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
        this.inventory = Bukkit.createInventory(this,
                GUIConfigManager.getRowsForLayout(config.getLayout(), 3) * 9,
                MiniMessageUtils.processComponent(title));
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
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), name, lore, infoConfig.getCustomModelData(), infoConfig.getItemModel(), infoConfig.getCraftEngineItem());
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
            ItemStack priceItem = ItemUtils.createStack(priceConfig.getMaterial(), name, lore, priceConfig.getCustomModelData(), priceConfig.getItemModel(), priceConfig.getCraftEngineItem());
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
                    ItemStack noMoneyItem = ItemUtils.createStack(noMoneyConfig.getMaterial(), noMoneyConfig.getName(), lore, noMoneyConfig.getCustomModelData(), noMoneyConfig.getItemModel(), noMoneyConfig.getCraftEngineItem());
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
                            refundAndForgetCopy(purchasedMusic, paidPrice);
                            return;
                        }
                        if (PublishedMusicManager.getInstance().recordSuccessfulPurchase(published.getUniqueId(), player, paidPrice)) {
                            if (PlayerMusicDiscHelper.giveDisc(player, purchasedMusic, published)) {
                                PublishedMusicManager.getInstance().rollbackRecordedPurchase(published.getUniqueId(), paidPrice);
                                refundAndForgetCopy(purchasedMusic, paidPrice);
                                MessageUtils.send(player, Lang.OPERATION_FAILED);
                                return;
                            }
                            completePurchase(Lang.PURCHASE_SUCCESS_MSG);
                        } else {
                            refundAndForgetCopy(purchasedMusic, paidPrice);
                            MessageUtils.send(player, Lang.PURCHASE_PAYMENT_FAILED);
                        }
                    }, purchasedMusic -> {
                        purchasing = false;
                        refundAndForgetCopy(purchasedMusic, paidPrice);
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
                                PlayerMusicManager.getInstance().discardUnsavedMusic(purchasedMusic.getUniqueId());
                                PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                                MessageUtils.send(player, Lang.OPERATION_FAILED);
                                return;
                            }
                            completePurchase(Lang.PURCHASE_AUTHOR_CLAIM_SUCCESS);
                        } else {
                            PlayerMusicManager.getInstance().discardUnsavedMusic(purchasedMusic.getUniqueId());
                            PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
                            MessageUtils.send(player, Lang.PURCHASE_OWN_MUSIC_MSG);
                        }
                    }, purchasedMusic -> {
                        purchasing = false;
                        // No money moved on this branch, but the copy must still be withdrawn.
                        PlayerMusicManager.getInstance().discardUnsavedMusic(purchasedMusic.getUniqueId());
                        PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
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

    private void savePurchasedMusic(Consumer<PlayerMusic> onSuccess, Consumer<PlayerMusic> onFailure) {
        PlayerMusic purchasedMusic = published.toPlayerMusic(player.getName(), player.getUniqueId());
        PlayerMusicManager.getInstance().saveMusicAsync(purchasedMusic, success -> {
            if (!success) {
                // The failure callback needs the id: the copy is already cached by the manager and
                // must be withdrawn, which the success-only signature made impossible.
                onFailure.accept(purchasedMusic);
                return;
            }
            onSuccess.accept(purchasedMusic);
        });
    }

    // Refunds the buyer and withdraws the song copy this purchase created.
    //
    // The copy is cached and queued for persistence before the write is attempted, so a refund that
    // only returns money would leave a playable, editable song in the buyer's library -- and the
    // pending save would still flush it to the database once storage recovered. Discarding locally
    // first is what makes the refund actually undo the purchase; the async delete then removes any
    // row an earlier attempt may have committed.
    private void refundAndForgetCopy(PlayerMusic purchasedMusic, double paidPrice) {
        PlayerMusicManager.getInstance().discardUnsavedMusic(purchasedMusic.getUniqueId());
        EconomyUtils.depositPlayer(player, paidPrice);
        PlayerMusicManager.getInstance().deleteMusicAsync(purchasedMusic.getUniqueId());
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
