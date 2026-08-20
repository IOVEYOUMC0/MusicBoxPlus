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
    private final Map<Integer, InventoryAction> runnableMap = new HashMap<Integer, InventoryAction>();
    private static volatile boolean listenerRegistered = false;
    private static final Object LISTENER_LOCK = new Object();
    private static GUIListener guiListener;

    public GUI(String title, int rows) {
        int slots;
        if (rows < 1 || rows > 6) {
            rows = 3;
        }
        if ((slots = rows * 9) < 9 || slots > 54) {
            rows = 3;
        }
        this.createInventory(title, rows);
    }

    private void createInventory(String title, int rows) {
        int slots = rows * 9;
        Component titleComponent = MiniMessageUtils.processComponent(title);
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GUI必须在主线程创建，请使用 BukkitUtils.runSyncTask() 包装调用");
        }
        try {
            this.inventory = Bukkit.createInventory(this, slots, titleComponent);
        }
        catch (Exception e) {
            MusicBox.getInstance().getLogger().severe("创建库存失败: " + e.getMessage());
            throw new RuntimeException("创建库存失败: " + e.getMessage(), e);
        }
    }
    
    public GUI(Component title, int rows) {
        int slots;
        if (rows < 1 || rows > 6) {
            rows = 3;
        }
        if ((slots = rows * 9) < 9 || slots > 54) {
            rows = 3;
        }
        this.createInventory(title, rows);
    }
    
    private void createInventory(Component title, int rows) {
        int slots = rows * 9;
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GUI必须在主线程创建，请使用 BukkitUtils.runSyncTask() 包装调用");
        }
        try {
            this.inventory = Bukkit.createInventory(this, slots, title);
        }
        catch (Exception e) {
            MusicBox.getInstance().getLogger().severe("创建库存失败: " + e.getMessage());
            throw new RuntimeException("创建库存失败: " + e.getMessage(), e);
        }
    }

    public GUI(String title) {
        this(title, 6);
    }
    
    public GUI(Component title) {
        this(title, 6);
    }

    public void open(Player player) {
        // Opening an inventory must run on the region that owns the player.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(player, () -> player.openInventory(this.inventory));
    }

    public void clear() {
        this.inventory.clear();
        this.runnableMap.clear();
    }

    public void addItem(int slot, ItemStack item, InventoryAction runnable) {
        this.inventory.setItem(slot, item);
        this.runnableMap.put(slot, runnable);
    }

    public void removeItem(int slot) {
        this.inventory.clear(slot);
        this.runnableMap.remove(slot);
    }
    
    public void updateItem(int slot, ItemStack item) {
        this.inventory.setItem(slot, item);
    }
    
    public void updateItem(int slot, ItemStack item, InventoryAction runnable) {
        this.inventory.setItem(slot, item);
        this.runnableMap.put(slot, runnable);
    }
    
    public boolean hasItem(int slot) {
        return this.runnableMap.containsKey(slot);
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
        ItemStack[] content = this.inventory.getContents();
        int slots = content.length;
        int rows = Math.min(6, Math.max(1, slots / 9));
        GUI gui = new GUI(title, rows);
        gui.runnableMap.putAll(this.runnableMap);
        ItemStack[] newContent = new ItemStack[rows * 9];
        System.arraycopy(content, 0, newContent, 0, Math.min(slots, newContent.length));
        gui.inventory.setContents(newContent);
        return gui;
    }
    
    public GUI cloneWithNewTitle(Component title) {
        ItemStack[] content = this.inventory.getContents();
        int slots = content.length;
        int rows = Math.min(6, Math.max(1, slots / 9));
        GUI gui = new GUI(title, rows);
        gui.runnableMap.putAll(this.runnableMap);
        ItemStack[] newContent = new ItemStack[rows * 9];
        System.arraycopy(content, 0, newContent, 0, Math.min(slots, newContent.length));
        gui.inventory.setContents(newContent);
        return gui;
    }

    public void setTitle(String title) {
        this.setTitle(MiniMessageUtils.processComponent(title));
    }

    public void setTitle(Component title) {
        ItemStack[] content = this.inventory.getContents();
        int slots = content.length;
        int rows = Math.min(6, Math.max(1, slots / 9));
        this.createInventory(title, rows);
        ItemStack[] newContent = new ItemStack[rows * 9];
        System.arraycopy(content, 0, newContent, 0, Math.min(slots, newContent.length));
        this.inventory.setContents(newContent);
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
    }
}
