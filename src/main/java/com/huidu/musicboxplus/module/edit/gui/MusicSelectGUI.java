package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MusicSelectGUI implements InventoryHolder {

    private static final Map<UUID, MusicSelectGUI> openGUIs = new ConcurrentHashMap<>();
    private final Player player;
    private final Inventory inventory;
    private final List<PlayerMusic> musicList;
    private final GUIConfigManager.MusicSelectConfig config;
    private final SelectMode mode;
    private int page = 0;

    public enum SelectMode {
        EDIT,
        DELETE,
        RENAME,
        EXPORT
    }

    public MusicSelectGUI(Player player) {
        this(player, SelectMode.EDIT);
    }

    public MusicSelectGUI(Player player, boolean deleteMode) {
        this(player, deleteMode ? SelectMode.DELETE : SelectMode.EDIT);
    }

    public MusicSelectGUI(Player player, SelectMode mode) {
        this.player = player;
        this.mode = mode == null ? SelectMode.EDIT : mode;
        this.config = GUIConfigManager.getInstance().getMusicSelectConfig();
        this.musicList = PlayerMusicManager.getInstance().getMusicByPlayer(player);
        String title = switch (this.mode) {
            case DELETE -> Lang.EDIT_DELETE_SELECT_TITLE.toString();
            case RENAME -> Lang.EDIT_RENAME_SELECT_TITLE.toString();
            case EXPORT -> Lang.EDIT_EXPORT_SELECT_TITLE.toString();
            default -> config.getTitle();
        };
        this.inventory = Bukkit.createInventory(this, 54, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int itemsPerPage = 45;
        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, musicList.size());

        List<Integer> musicSlots = config.getSlotsForChar(config.getButtonMapping().getOrDefault("music-item", 'M'));
        
        for (int i = startIndex; i < endIndex; i++) {
            int slotIndex = i - startIndex;
            if (slotIndex >= musicSlots.size()) break;
            
            int slot = musicSlots.get(slotIndex);
            PlayerMusic music = musicList.get(i);
            
            GUIConfigManager.HotbarButtonConfig musicConfig = config.getButton("music-item");
            List<String> lore = new ArrayList<>();
            for (String line : musicConfig.getLore()) {
                lore.add(line.replace("{name}", music.getName())
                        .replace("{notes}", String.valueOf(music.getNoteCount()))
                        .replace("{bpm}", String.valueOf(music.getBpm()))
                        .replace("{timeSignature}", music.getTimeSignature().toString()));
            }
            if (mode != SelectMode.EDIT) {
                lore.add("");
                lore.add(getModeLore());
            }
            String name = musicConfig.getName()
                    .replace("{name}", music.getName())
                    .replace("{modeColor}", mode == SelectMode.DELETE ? "<red>" : "<yellow>");
            ItemStack item = ItemUtils.createStack(musicConfig.getMaterial(), name, lore, musicConfig.getCustomModelData());
            inventory.setItem(slot, item);
        }

        int prevSlot = config.getSlotForButton("prev-page");
        if (prevSlot >= 0 && page > 0) {
            GUIConfigManager.HotbarButtonConfig prevConfig = config.getButton("prev-page");
            List<String> lore = new ArrayList<>();
            for (String line : prevConfig.getLore()) {
                lore.add(line.replace("{page}", String.valueOf(page + 1))
                        .replace("{totalPages}", String.valueOf(getTotalPages())));
            }
            ItemStack prevButton = ItemUtils.createStack(prevConfig.getMaterial(), prevConfig.getName(), lore, prevConfig.getCustomModelData());
            inventory.setItem(prevSlot, prevButton);
        }

        int nextSlot = config.getSlotForButton("next-page");
        if (nextSlot >= 0 && (page + 1) * itemsPerPage < musicList.size()) {
            GUIConfigManager.HotbarButtonConfig nextConfig = config.getButton("next-page");
            List<String> lore = new ArrayList<>();
            for (String line : nextConfig.getLore()) {
                lore.add(line.replace("{page}", String.valueOf(page + 1))
                        .replace("{totalPages}", String.valueOf(getTotalPages())));
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

        int createSlot = config.getSlotForButton("create");
        if (createSlot >= 0 && mode == SelectMode.EDIT) {
            GUIConfigManager.HotbarButtonConfig createConfig = config.getButton("create");
            ItemStack createButton = createConfig.createItem();
            inventory.setItem(createSlot, createButton);
        }
    }

    private int getTotalPages() {
        return Math.max(1, (int) Math.ceil((double) musicList.size() / 45.0));
    }

    public void handleClick(int slot) {
        int itemsPerPage = 45;
        int prevSlot = config.getSlotForButton("prev-page");
        int nextSlot = config.getSlotForButton("next-page");
        int closeSlot = config.getSlotForButton("close");
        int createSlot = config.getSlotForButton("create");
        List<Integer> musicSlots = config.getSlotsForChar(config.getButtonMapping().getOrDefault("music-item", 'M'));

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

        if (slot == createSlot && mode == SelectMode.EDIT) {
            player.closeInventory();
            MusicCreateFlow.start(player);
            return;
        }

        int musicIndex = musicSlots.indexOf(slot);
        if (musicIndex >= 0) {
            int index = page * itemsPerPage + musicIndex;
            if (index < musicList.size()) {
                PlayerMusic selectedMusic = musicList.get(index);
                
                player.closeInventory();
                handleSelectedMusic(selectedMusic);
            }
        }
    }

    private String getModeLore() {
        return switch (mode) {
            case DELETE -> Lang.EDIT_DELETE_CLICK_LORE.toString();
            case RENAME -> Lang.EDIT_RENAME_CLICK_LORE.toString();
            case EXPORT -> Lang.EDIT_EXPORT_CLICK_LORE.toString();
            default -> "";
        };
    }

    private void handleSelectedMusic(PlayerMusic selectedMusic) {
        switch (mode) {
            case DELETE -> openDeleteConfirm(selectedMusic);
            case RENAME -> startRenameFlow(selectedMusic);
            case EXPORT -> exportMusic(selectedMusic);
            default -> MusicEditListener.enterEditMode(player, selectedMusic);
        }
    }

    private void openDeleteConfirm(PlayerMusic selectedMusic) {
        DeleteConfirmGUI confirmGUI = new DeleteConfirmGUI(
            player,
            selectedMusic.getName(),
            () -> {
                PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
                String musicName = selectedMusic.getName();
                musicManager.deleteMusicAsync(selectedMusic.getUniqueId()).thenAccept(success ->
                    Scheduler.entity(player, () -> {
                        if (success) {
                            MessageUtils.send(player, Lang.EDIT_DELETE_SUCCESS, "{name}", musicName);
                        } else {
                            MessageUtils.send(player, Lang.EDIT_DELETE_FAILED_MSG);
                        }
                    })
                );
            },
            () -> MessageUtils.send(player, Lang.EDIT_DELETE_CANCELLED)
        );
        confirmGUI.open();
    }

    private void startRenameFlow(PlayerMusic selectedMusic) {
        GUIInputManager.getInstance().requestInput(player, GUIInputManager.InputType.SEARCH_QUERY, Lang.EDIT_RENAME_USAGE.toComponent(), new GUIInputManager.InputCallback() {
            @Override
            public void onInputReceived(Player p, String input) {
                Scheduler.entity(player, () -> {
                    String newName = input == null ? "" : input.trim();
                    if (newName.isEmpty()) {
                        MessageUtils.send(player, Lang.EDIT_RENAME_FAILED_MSG);
                        return;
                    }
                    PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
                    PlayerMusic existing = musicManager.getMusicByName(player.getUniqueId(), newName);
                    if (existing != null && !existing.getUniqueId().equals(selectedMusic.getUniqueId())) {
                        MessageUtils.send(player, Lang.EDIT_RENAME_EXISTS, "{name}", newName);
                        return;
                    }
                    String oldName = selectedMusic.getName();
                    musicManager.renameMusicAsync(selectedMusic.getUniqueId(), newName).thenAccept(success ->
                        Scheduler.entity(player, () -> {
                            if (success) {
                                MessageUtils.send(player, Lang.EDIT_RENAME_SUCCESS, "{old}", oldName, "{new}", newName);
                            } else {
                                MessageUtils.send(player, Lang.EDIT_RENAME_FAILED_MSG);
                            }
                        })
                    );
                });
            }

            @Override
            public void onInputCancelled(Player p) {
                MessageUtils.send(p, Lang.CANCELLED);
            }
        });
    }

    private void exportMusic(PlayerMusic selectedMusic) {
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                com.huidu.musicboxplus.module.edit.io.NBSExporter.ExportResult result = com.huidu.musicboxplus.module.edit.io.NBSExporter.getInstance().export(selectedMusic);
                Scheduler.entity(player, () -> MessageUtils.send(player, Lang.EXPORT_SUCCESS,
                    "{name}", selectedMusic.getName(),
                    "{file}", result.file().getName(),
                    "{path}", result.file().getPath()));
            } catch (Exception e) {
                Scheduler.entity(player,
                    () -> MessageUtils.send(player, Lang.EXPORT_FAILED, "{error}", e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        });
    }

    public void open() {
        openGUIs.put(player.getUniqueId(), this);
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }


    public static MusicSelectGUI getOpenGUI(UUID playerUUID) {
        return openGUIs.get(playerUUID);
    }

    public static void removeOpenGUI(UUID playerUUID) {
        openGUIs.remove(playerUUID);
    }

    public static boolean hasOpenGUI(UUID playerUUID) {
        return openGUIs.containsKey(playerUUID);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
