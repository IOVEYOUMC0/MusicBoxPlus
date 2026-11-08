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
                MusicBox.getInstance().getLogger().warning(LogLocale.text(MusicBox.getInstance(),
                        "Vault is not installed or is incompatible; economy features will be unavailable",
                        "Vault 未安装或版本不兼容，经济功能将不可用"));
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().warning(LogLocale.text(MusicBox.getInstance(),
                        "Failed to initialize the economy system: " + e.getMessage(),
                        "初始化经济系统失败: " + e.getMessage()));
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
    // resolved. Every money-touching method below asks the mode first; keeping the decision in one
    // place means a newly added purchase path cannot miss it.
    //
    // The three modes are deliberately not collapsed into a boolean: "the admin turned prices off"
    // and "prices are on but no provider loaded" look identical to a nullable Economy field, and
    // treating the second as the first hands out priced goods for free.
    public static EconomyGate.Mode mode() {
        ensureInitialized();
        boolean enabled = MusicBox.getInstance().getConfigObject().getEconomy().isEnable();
        return EconomyGate.mode(enabled, eco != null);
    }

    // True only when the server deliberately turned money off, so purchases really are free.
    public static boolean isDisabled() {
        return mode() == EconomyGate.Mode.FREE;
    }

    // Economy is configured on but cannot charge anyone: purchases must fail closed.
    public static boolean isUnavailable() {
        return mode() == EconomyGate.Mode.UNAVAILABLE;
    }

    // With economy off the price is always 0, i.e. discs are handed out for free. With the economy
    // enabled-but-unavailable the real price is still shown, so the buyer gets an explicit refusal
    // instead of a purchase that silently costs nothing.
    public static double getDiscPrice() {
        EconomyGate.Mode mode = mode();
        if (mode == EconomyGate.Mode.FREE) {
            return 0.0;
        }
        // Never allow a negative price, which would credit the buyer on withdraw.
        return EconomyGate.price(mode, MusicBox.getInstance().getConfigObject().getEconomy().getPrice());
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
        EconomyGate.Mode mode = mode();
        if (mode == EconomyGate.Mode.FREE) {
            return true;
        }
        if (mode == EconomyGate.Mode.UNAVAILABLE) {
            return false;
        }
        return EconomyGate.canAfford(mode, getBalance(player), money);
    }

    // With economy off nothing is withdrawn and the purchase counts as successful (free).
    // An enabled-but-unavailable economy charges nothing, so it must NOT report success.
    public static boolean buyNoMessage(Player player, double price) {
        EconomyGate.Mode mode = mode();
        if (mode == EconomyGate.Mode.FREE) {
            return true;
        }
        if (mode == EconomyGate.Mode.UNAVAILABLE) {
            MusicBox.getInstance().getLogger().warning(
                    "Economy is enabled but no Vault provider is available; purchase refused");
            return false;
        }
        EconomyResponse response = eco.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            MusicBox.getInstance().getLogger().warning(LogLocale.text(MusicBox.getInstance(),
                    "Failed to withdraw " + price + " from player " + player.getName() + ": " + response.errorMessage,
                    "从玩家 " + player.getName() + " 扣款 " + price + " 失败: " + response.errorMessage));
            return false;
        }
        return true;
    }

    // Returns true when the player must be refused (balance too low, no economy backing, or the
    // economy is enabled without a provider).
    // The inverted reading of this name is the entire point: every call site is a rejection
    // branch like "if (cannotBuy(...)) refuse", so a future caller cannot flip the logic by
    // accident. With economy off this always passes and stays silent about the economy being
    // unavailable.
    public static boolean cannotBuy(Player player, double price) {
        EconomyGate.Mode mode = mode();
        if (mode == EconomyGate.Mode.FREE) {
            return false;
        }
        if (mode == EconomyGate.Mode.UNAVAILABLE) {
            MessageUtils.send(player, Lang.ERROR, "{message}", "Economy system not available");
            return true;
        }
        double currentMoney = getBalance(player);
        if (EconomyGate.canAfford(mode, currentMoney, price)) {
            return false;
        }
        MessageUtils.send(player, Lang.NO_HAS_MONEY, "{amount}",
                String.valueOf(EconomyGate.shortfall(currentMoney, price)));
        return true;
    }

    // With economy off nothing is paid out; true then means "no payout needed", so callers must
    // not read it as the player having received money
    public static boolean depositPlayer(Player player, double amount) {
        // false, not true: the caller deletes the pending revenue on success, and with no economy
        // the money was never paid out.
        EconomyGate.Mode mode = mode();
        if (mode != EconomyGate.Mode.ACTIVE) {
            return false;
        }
        EconomyResponse response = eco.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            MusicBox.getInstance().getLogger().warning(LogLocale.text(MusicBox.getInstance(),
                    "Failed to deposit " + amount + " to player " + player.getName() + ": " + response.errorMessage,
                    "向玩家 " + player.getName() + " 存款 " + amount + " 失败: " + response.errorMessage));
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
