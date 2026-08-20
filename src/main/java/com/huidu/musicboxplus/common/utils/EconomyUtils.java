package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.lang.Lang;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

// Vault marks its own Economy/EconomyResponse API deprecated, but Vault is still the economy
// standard every provider implements, so the suppression stays with the integration.
@SuppressWarnings("deprecation")
public final class EconomyUtils {
    private static volatile Economy eco = null;
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    public static void initialize() {
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            try {
                if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                    RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
                    if (registration != null) {
                        eco = registration.getProvider();
                    }
                }
            } catch (NoClassDefFoundError e) {
                MusicBox.getInstance().getLogger().warning("Vault 未安装或版本不兼容，经济功能将不可用");
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().warning("初始化经济系统失败: " + e.getMessage());
            }
            initialized = true;
        }
    }
    
    public static void reload() {
        synchronized (initLock) {
            initialized = false;
            eco = null;
            initialize();
        }
    }

    // Economy is actually usable only when the config switch is on and a Vault economy service
    // resolved. Every money-touching method below asks this first; keeping the check in one place
    // instead of at each purchase entry point means a newly added purchase path cannot miss it.
    // True when there is no economy to move money through: either the feature is off or no Vault
    // provider registered. Named for what it returns -- the old isEnable() meant the opposite of
    // how it reads, and depositPlayer's `if (isDisabled()) return true` silently reported success
    // for a deposit that never happened.
    public static boolean isDisabled() {
        ensureInitialized();
        return !MusicBox.getInstance().getConfigObject().getEconomy().isEnable() || eco == null;
    }

    // With economy off the price is always 0, i.e. discs are handed out for free
    public static double getDiscPrice() {
        if (isDisabled()) {
            return 0.0;
        }
        // Never allow a negative price, which would credit the buyer on withdraw.
        return Math.max(0, MusicBox.getInstance().getConfigObject().getEconomy().getPrice());
    }

    public static double getBalance(Player player) {
        ensureInitialized();
        if (eco == null) {
            return 0.0;
        }
        return eco.getBalance(player);
    }

    // With economy off everyone is treated as able to afford anything
    public static boolean hasMoney(Player player, double money) {
        if (isDisabled()) {
            return true;
        }
        ensureInitialized();
        if (eco == null) {
            return false;
        }
        double currentMoney = EconomyUtils.getBalance(player);
        return currentMoney - money >= 0.0;
    }

    // With economy off nothing is withdrawn and the purchase counts as successful (free)
    public static boolean buyNoMessage(Player player, double price) {
        if (isDisabled()) {
            return true;
        }
        ensureInitialized();
        if (eco != null) {
            EconomyResponse response = eco.withdrawPlayer(player, price);
            if (!response.transactionSuccess()) {
                MusicBox.getInstance().getLogger().warning("从玩家 " + player.getName() + " 扣款 " + price + " 失败: " + response.errorMessage);
                return false;
            }
            return true;
        }
        return false;
    }

    // Returns true when the player must be refused (balance too low, or no economy backing).
    // The inverted reading of this name is the entire point: every call site is a rejection
    // branch like "if (cannotBuy(...)) refuse", so a future caller cannot flip the logic by
    // accident. With economy off this always passes and stays silent about the economy being
    // unavailable.
    public static boolean cannotBuy(Player player, double price) {
        if (isDisabled()) {
            return false;
        }
        ensureInitialized();
        if (eco == null) {
            MessageUtils.send(player, Lang.ERROR, "{message}", "Economy system not available");
            return true;
        }
        double currentMoney = EconomyUtils.getBalance(player);
        double moneyLeft = currentMoney - price;
        if (moneyLeft >= 0.0) {
            return false;
        }
        MessageUtils.send(player, Lang.NO_HAS_MONEY, "{amount}", String.valueOf(moneyLeft *= -1.0));
        return true;
    }

    // With economy off nothing is paid out; true then means "no payout needed", so callers must
    // not read it as the player having received money
    public static boolean depositPlayer(Player player, double amount) {
        // false, not true: the caller deletes the pending revenue on success, and with no economy
        // the money was never paid out.
        if (isDisabled()) {
            return false;
        }
        ensureInitialized();
        if (eco == null) {
            return false;
        }
        EconomyResponse response = eco.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            MusicBox.getInstance().getLogger().warning("向玩家 " + player.getName() + " 存款 " + amount + " 失败: " + response.errorMessage);
            return false;
        }
        return true;
    }
    
    private static void ensureInitialized() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    initialize();
                }
            }
        }
    }

    private EconomyUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
