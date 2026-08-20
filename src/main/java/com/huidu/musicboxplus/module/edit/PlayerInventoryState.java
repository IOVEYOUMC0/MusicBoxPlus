package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerInventoryState {

    private static final Map<UUID, PlayerInventoryState> savedStates = new ConcurrentHashMap<>();
    private static volatile File saveFolder = null;
    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    public static void initialize() {
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            MusicBox instance = MusicBox.getInstance();
            if (instance == null) {
                return;
            }
            saveFolder = resolveBackupFolder(instance);
            if (!saveFolder.exists()) {
                saveFolder.mkdirs();
            }
            loadAllSavedStates();
            initialized = true;
        }
    }

    // Reads storage.inventoryBackupFolder: an absolute path is used as-is, a relative one
    // resolves under the plugin data folder.
    private static File resolveBackupFolder(MusicBox instance) {
        String folderName = null;
        MusicBoxConfig config = instance.getConfigObject();
        if (config != null && config.getStorage() != null) {
            folderName = config.getStorage().getInventoryBackupFolder();
        }
        if (folderName == null || folderName.trim().isEmpty()) {
            folderName = "inventory_backups";
        }
        File folder = new File(folderName.trim());
        return folder.isAbsolute() ? folder : new File(instance.getDataFolder(), folderName.trim());
    }
    
    private final UUID playerUUID;
    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final int heldSlot;
    private final long savedTime;

    public PlayerInventoryState(Player player) {
        this.playerUUID = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        this.contents = new ItemStack[inv.getContents().length];
        for (int i = 0; i < inv.getContents().length; i++) {
            ItemStack item = inv.getContents()[i];
            this.contents[i] = item != null ? item.clone() : null;
        }
        this.armor = new ItemStack[inv.getArmorContents().length];
        ItemStack[] armorContents = inv.getArmorContents();
        for (int i = 0; i < armorContents.length; i++) {
            this.armor[i] = armorContents[i] != null ? armorContents[i].clone() : null;
        }
        this.offHand = inv.getItemInOffHand() != null ? inv.getItemInOffHand().clone() : null;
        this.heldSlot = inv.getHeldItemSlot();
        this.savedTime = System.currentTimeMillis();
    }

    private PlayerInventoryState(UUID playerUUID, ItemStack[] contents, ItemStack[] armor, 
                                  ItemStack offHand, int heldSlot, long savedTime) {
        this.playerUUID = playerUUID;
        this.contents = contents;
        this.armor = armor;
        this.offHand = offHand;
        this.heldSlot = heldSlot;
        this.savedTime = savedTime;
    }

    public void restore(Player player) {
        PlayerInventory inv = player.getInventory();
        player.setItemOnCursor(new ItemStack(Material.AIR));
        inv.clear();
        inv.setContents(contents);
        inv.setArmorContents(armor);
        inv.setItemInOffHand(offHand);
        inv.setHeldItemSlot(heldSlot);
        player.updateInventory();
    }

    public void saveToDisk() {
        if (saveFolder == null) {
            // initialize() has not run yet; skip disk persistence rather than
            // writing the backup to the server working directory (where it would
            // never be found/loaded again).
            return;
        }
        File file = new File(saveFolder, playerUUID.toString() + ".dat");
        File tmp = new File(saveFolder, playerUUID.toString() + ".dat.tmp");
        try {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tmp))) {
                oos.writeObject(playerUUID.toString());
                oos.writeObject(serializeItemStackArray(contents));
                oos.writeObject(serializeItemStackArray(armor));
                oos.writeObject(serializeItemStackArray(new ItemStack[]{offHand}));
                oos.writeInt(heldSlot);
                oos.writeLong(savedTime);
            }
            // The backup is fully written and closed; swap it into place. A crash mid-write
            // truncates only the temp file, never the real backup the player needs to recover.
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            MusicBox.getInstance().getLogger().warning("保存玩家物品栏状态失败: " + playerUUID + " - " + e.getMessage());
            tmp.delete();
        }
    }

    public void deleteFromDisk() {
        if (saveFolder == null) {
            return;
        }
        File file = new File(saveFolder, playerUUID.toString() + ".dat");
        if (file.exists()) {
            file.delete();
        }
    }

    private static byte[] serializeItemStackArray(ItemStack[] items) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(items.length);
            for (ItemStack item : items) {
                if (item != null && item.getType() != Material.AIR) {
                    dos.writeBoolean(true);
                    byte[] serialized = item.serializeAsBytes();
                    dos.writeInt(serialized.length);
                    dos.write(serialized);
                } else {
                    dos.writeBoolean(false);
                }
            }
        }
        return baos.toByteArray();
    }

    private static ItemStack[] deserializeItemStackArray(byte[] data) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int length = dis.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                boolean hasItem = dis.readBoolean();
                if (hasItem) {
                    int itemLength = dis.readInt();
                    byte[] itemData = new byte[itemLength];
                    dis.readFully(itemData);
                    items[i] = ItemStack.deserializeBytes(itemData);
                } else {
                    items[i] = null;
                }
            }
            return items;
        }
    }

    public static PlayerInventoryState loadFromDisk(UUID playerUUID) {
        File file = new File(saveFolder, playerUUID.toString() + ".dat");
        if (!file.exists()) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            String uuidStr = (String) ois.readObject();
            byte[] contentsData = (byte[]) ois.readObject();
            byte[] armorData = (byte[]) ois.readObject();
            byte[] offHandData = (byte[]) ois.readObject();
            int heldSlot = ois.readInt();
            long savedTime = ois.readLong();

            ItemStack[] contents = deserializeItemStackArray(contentsData);
            ItemStack[] armor = deserializeItemStackArray(armorData);
            ItemStack[] offHand = deserializeItemStackArray(offHandData);

            return new PlayerInventoryState(UUID.fromString(uuidStr), contents, armor, 
                    offHand.length > 0 ? offHand[0] : null, heldSlot, savedTime);
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().warning("加载玩家物品栏状态失败: " + playerUUID + " - " + e.getMessage());
            return null;
        }
    }

    public static void loadAllSavedStates() {
        File[] files = saveFolder.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                String fileName = file.getName().replace(".dat", "");
                UUID playerUUID = UUID.fromString(fileName);
                PlayerInventoryState state = loadFromDisk(playerUUID);
                if (state != null) {
                    savedStates.put(playerUUID, state);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (!savedStates.isEmpty()) {
            MusicBox.getInstance().getLogger().info("已加载 " + savedStates.size() + " 个保存的物品栏状态");
        }
    }

    public static void saveState(Player player) {
        PlayerInventoryState existing = savedStates.get(player.getUniqueId());
        if (existing != null) {
            return;
        }
        PlayerInventoryState state = new PlayerInventoryState(player);
        savedStates.put(player.getUniqueId(), state);
        state.saveToDisk();
    }

    public static void saveStateForced(Player player) {
        PlayerInventoryState state = new PlayerInventoryState(player);
        savedStates.put(player.getUniqueId(), state);
        state.saveToDisk();
    }

    public static boolean hasSavedState(UUID playerUUID) {
        return savedStates.containsKey(playerUUID);
    }

    public static PlayerInventoryState getSavedState(UUID playerUUID) {
        return savedStates.get(playerUUID);
    }

    public static void restoreAndRemove(Player player) {
        PlayerInventoryState state = savedStates.remove(player.getUniqueId());
        if (state != null) {
            state.restore(player);
            state.deleteFromDisk();
            MessageUtils.send(player, Lang.EDIT_INVENTORY_RESTORED);
        }
    }

    // Drops only what was actually restored. A blanket clear afterwards also discarded the
    // offline entries the else branch deliberately keeps, and nothing ever reloads them:
    // loadAllSavedStates runs only from initialize(), which is one-shot. The .dat stayed on disk
    // where no code path could see it, and the player rejoined holding editor items instead of
    // their own inventory.
    public static void restoreAllPending() {
        savedStates.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                MusicBox.getInstance().getLogger().info("玩家 " + entry.getKey() + " 不在线，保留物品栏状态等待下次登录");
                return false;
            }
            entry.getValue().restore(player);
            MessageUtils.send(player, Lang.EDIT_MODE_EXITED);
            entry.getValue().deleteFromDisk();
            return true;
        });
    }

    public static void removeState(UUID playerUUID) {
        PlayerInventoryState state = savedStates.remove(playerUUID);
        if (state != null) {
            state.deleteFromDisk();
        }
    }

    public static void clearStateOnly(UUID playerUUID) {
        savedStates.remove(playerUUID);
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public long getSavedTime() {
        return savedTime;
    }
}
