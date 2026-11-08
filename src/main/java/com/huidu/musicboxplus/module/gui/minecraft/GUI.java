package com.huidu.musicboxplus.module.gui.minecraft;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.SoundUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class GUI
implements InventoryHolder {
    private Inventory inventory;
    private final Map<Integer, InventoryAction> runnableMap = new HashMap<>();
    private static volatile boolean listenerRegistered = false;
    private static final Object LISTENER_LOCK = new Object();
    private static GUIListener guiListener;

    public GUI(String title, int rows) {
        // One parsing path: MiniMessage runs here for both overloads instead of the two separate
        // createInventory copies this class used to carry.
        this(MiniMessageUtils.processComponent(title), rows);
    }

    public GUI(Component title, int rows) {
        createInventory(title, clampRows(rows));
    }

    public GUI(String title) {
        this(title, 6);
    }

    public GUI(Component title) {
        this(title, 6);
    }

    // A menu is 1..6 rows. Anything else is a caller bug; falling back to 3 keeps the old behaviour
    // without the second, unreachable bounds check on the slot count.
    private static int clampRows(int rows) {
        return rows >= 1 && rows <= 6 ? rows : 3;
    }

    private void createInventory(Component title, int rows) {
        // "primary thread" means "a server tick thread" here: on Folia Bukkit.isPrimaryThread() is
        // CraftServer.isPrimaryThread() -> TickThread.isTickThread(), which is true on EVERY region
        // thread, not just the global one. That is what this guard is for -- it catches an async or
        // HTTP thread creating a GUI (the real bug it was added for) -- but it is not an ownership
        // test, so nothing here may use it to decide whether world or entity state is reachable.
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GUI必须在服务器线程创建，请使用 Scheduler.global() 包装调用");
        }
        try {
            this.inventory = Bukkit.createInventory(this, rows * 9, title);
        }
        catch (Exception e) {
            MusicBox.getInstance().getLogger().severe("创建库存失败: " + e.getMessage());
            throw new RuntimeException("创建库存失败: " + e.getMessage(), e);
        }
    }

    public void open(Player player) {
        if (player == null) {
            return;
        }
        // Opening an inventory must run on the region that owns the player.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(player, () -> player.openInventory(this.inventory));
    }

    public void clear() {
        this.inventory.clear();
        this.runnableMap.clear();
    }

    public void addItem(int slot, ItemStack item, InventoryAction runnable) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.inventory.setItem(slot, item);
        this.runnableMap.put(slot, runnable);
    }

    public void removeItem(int slot) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.inventory.clear(slot);
        this.runnableMap.remove(slot);
    }

    public void updateItem(int slot, ItemStack item) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.inventory.setItem(slot, item);
    }

    public void updateItem(int slot, ItemStack item, InventoryAction runnable) {
        if (!isValidSlot(slot)) {
            return;
        }
        this.inventory.setItem(slot, item);
        this.runnableMap.put(slot, runnable);
    }

    public boolean hasItem(int slot) {
        return isValidSlot(slot) && this.runnableMap.containsKey(slot);
    }

    private boolean isValidSlot(int slot) {
        return slot >= 0 && slot < this.inventory.getSize();
    }

    public void onInventoryClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() == null) {
            return;
        }
        InventoryHolder clickedHolder = e.getClickedInventory().getHolder();
        if (clickedHolder != this) {
            return;
        }
        InventoryAction action = this.runnableMap.get(e.getSlot());
        if (action == null) {
            return;
        }
        if (e.getWhoClicked() instanceof Player) {
            Player player = (Player)e.getWhoClicked();
            SoundUtils.playClickSound(player);
        }
        action.onEvent(e);
    }

    public GUI cloneWithNewTitle(String title) {
        return cloneWithNewTitle(MiniMessageUtils.processComponent(title));
    }

    public GUI cloneWithNewTitle(Component title) {
        ItemStack[] content = this.inventory.getContents();
        int rows = rowsOf(content.length);
        GUI gui = new GUI(title, rows);
        gui.runnableMap.putAll(this.runnableMap);
        ItemStack[] newContent = new ItemStack[rows * 9];
        System.arraycopy(content, 0, newContent, 0, Math.min(content.length, newContent.length));
        gui.inventory.setContents(newContent);
        return gui;
    }

    public void setTitle(String title) {
        this.setTitle(MiniMessageUtils.processComponent(title));
    }

    public void setTitle(Component title) {
        ItemStack[] content = this.inventory.getContents();
        int rows = rowsOf(content.length);
        this.createInventory(title, rows);
        ItemStack[] newContent = new ItemStack[rows * 9];
        System.arraycopy(content, 0, newContent, 0, Math.min(content.length, newContent.length));
        this.inventory.setContents(newContent);
    }

    // Slot count to row count, used by both title-replacing paths. An inventory is always a whole
    // number of rows, so this cannot round down into a smaller menu except for an empty one.
    private static int rowsOf(int slots) {
        return Math.min(6, Math.max(1, slots / 9));
    }

    public Inventory getInventory() {
        return this.inventory;
    }

    public static void registerListener() {
        if (!listenerRegistered) {
            synchronized (LISTENER_LOCK) {
                if (!listenerRegistered) {
                    guiListener = new GUIListener();
                    Bukkit.getPluginManager().registerEvents(guiListener, MusicBox.getInstance());
                    listenerRegistered = true;
                }
            }
        }
    }

    public static void unregisterListener() {
        if (listenerRegistered && guiListener != null) {
            synchronized (LISTENER_LOCK) {
                if (listenerRegistered && guiListener != null) {
                    org.bukkit.event.HandlerList.unregisterAll(guiListener);
                    guiListener = null;
                    listenerRegistered = false;
                }
            }
        }
    }

    public static boolean isListenerRegistered() {
        return listenerRegistered;
    }

    private static class GUIListener
    implements Listener {
        private GUIListener() {
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            Inventory inventory = e.getInventory();
            InventoryHolder holder = inventory.getHolder();
            if (holder == null) {
                return;
            }
            if (holder instanceof GUI) {
                ((GUI)holder).onInventoryClick(e);
            }
        }

        // Cancelling clicks alone left every menu open to dragging: an item dragged into a display
        // slot stays in the top inventory until the next repaint clears it, which deletes it.
        // Only drags that touch the plugin-owned top inventory are cancelled, so a player can still
        // reorganise their own inventory while a menu is open.
        @EventHandler
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
            Inventory top = e.getView().getTopInventory();
            if (!(top.getHolder() instanceof GUI)) {
                return;
            }
            int topSize = top.getSize();
            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot < topSize) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }
}
