package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.*;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.io.MusicFileImporter;
import com.huidu.musicboxplus.module.edit.io.MusicFileImporter.ImportResult;
import com.huidu.musicboxplus.module.edit.io.NBSExporter;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class EditMenuGUI implements InventoryHolder {

    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.EditMenuConfig config;

    public EditMenuGUI(Player player) {
        this.player = player;
        this.config = GUIConfigManager.getInstance().getEditMenuConfig();
        this.inventory = Bukkit.createInventory(this, 36, MiniMessageUtils.processComponent(config.getTitle()));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        setButton("create");
        setButton("select");
        setButton("delete");
        setButton("rename");
        setButton("list");
        setButton("import");
        setButton("export");
        setButton("back");
    }

    private void setButton(String key) {
        int slot = config.getSlotForButton(key);
        if (slot < 0) {
            return;
        }
        if (!key.equals("back") && !player.hasPermission(Permissions.EDIT)) {
            return;
        }
        GUIConfigManager.HotbarButtonConfig buttonConfig = config.getButton(key);
        ItemStack item = ItemUtils.createStack(
                buttonConfig.getMaterial(),
                buttonConfig.getName(),
                buttonConfig.getLore(),
                buttonConfig.getCustomModelData()
        );
        inventory.setItem(slot, item);
    }

    public void handleClick(int slot) {
        int createSlot = config.getSlotForButton("create");
        int selectSlot = config.getSlotForButton("select");
        int deleteSlot = config.getSlotForButton("delete");
        int renameSlot = config.getSlotForButton("rename");
        int listSlot = config.getSlotForButton("list");
        int importSlot = config.getSlotForButton("import");
        int exportSlot = config.getSlotForButton("export");
        int backSlot = config.getSlotForButton("back");

        if (slot == createSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            MusicCreateFlow.start(player);
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == selectSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            openMusicSelect();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == deleteSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            openDeleteSelect();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == renameSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            openRenameSelect();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == listSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            showMusicList();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == importSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            startImportFlow();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == exportSlot && player.hasPermission(Permissions.EDIT)) {
            player.closeInventory();
            startExportFlow();
            SoundUtils.playClickSound(player);
            return;
        }
        if (slot == backSlot) {
            player.closeInventory();
            SoundUtils.playClickSound(player);
        }
    }


    private void openMusicSelect() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_MUSIC);
            return;
        }

        if (musicList.size() == 1) {
            MusicEditListener.enterEditMode(player, musicList.get(0));
        } else {
            new MusicSelectGUI(player).open();
        }
    }

    private void openDeleteSelect() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_DELETE_NOT_FOUND, "{name}", "");
            return;
        }

        if (musicList.size() == 1) {
            PlayerMusic music = musicList.get(0);
            String musicName = music.getName();
            musicManager.deleteMusicAsync(music.getUniqueId()).thenAccept(success ->
                    Scheduler.entity(player, () -> {
                        if (success) {
                            MessageUtils.send(player, Lang.EDIT_DELETE_SUCCESS, "{name}", musicName);
                        } else {
                            MessageUtils.send(player, Lang.EDIT_DELETE_FAILED_MSG);
                        }
                    })
            );
        } else {
            new MusicSelectGUI(player, true).open();
        }
    }

    private void openRenameSelect() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_RENAME_NOT_FOUND, "{name}", "");
            return;
        }

        new MusicSelectGUI(player, MusicSelectGUI.SelectMode.RENAME).open();
    }

    private void showMusicList() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_LIST_EMPTY);
            return;
        }

        MessageUtils.send(player, Lang.EDIT_LIST_TITLE);
        for (PlayerMusic music : musicList) {
            MessageUtils.send(player, Lang.EDIT_LIST_ITEM.toString(
                    "{name}", music.getName(),
                    "{timeSignature}", music.getTimeSignature().toString(),
                    "{notes}", String.valueOf(music.getNoteCount()))
            );
        }
    }

    private void startImportFlow() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();

        if (musicManager.canCreateMore(player)) {
            int limit = musicManager.getMusicLimit(player);
            MessageUtils.send(player, Lang.EDIT_CREATE_LIMIT, "{limit}", String.valueOf(limit));
            return;
        }

        MessageUtils.send(player, Lang.IMPORT_INPUT_PROMPT);
        MessageUtils.send(player, Lang.IMPORT_FILE_LOCATION);
        GUIInputManager.getInstance().requestInput(player, fileName -> {
            Scheduler.entity(player, () -> {
                if (fileName == null || fileName.trim().isEmpty()) {
                    MessageUtils.send(player, Lang.IMPORT_FILENAME_EMPTY);
                    return;
                }

                String trimmedName = fileName.trim();
                String authorName = player.getName();
                java.util.UUID authorUUID = player.getUniqueId();
                AsyncTaskManager.runAsync(() -> {
                    try {
                        ImportResult result = MusicFileImporter.getInstance().importByName(trimmedName, authorName, authorUUID);
                        Scheduler.entity(player, () -> {
                            MessageUtils.send(player, Lang.IMPORT_SUCCESS,
                                    "{format}", result.format().getDisplayName(),
                                    "{name}", result.music().getName());
                            MessageUtils.send(player, Lang.IMPORT_INFO,
                                    "{notes}", String.valueOf(result.music().getNoteCount()),
                                    "{bpm}", String.valueOf(result.music().getBpm()),
                                    "{subdivision}", String.valueOf(result.music().getBeatSubdivision()));
                            sendImportWarnings(result);
                            MusicEditListener.enterEditMode(player, result.music());
                        });
                    } catch (Exception e) {
                        Scheduler.entity(player, () -> {
                            String message = e.getMessage() == null ? "Unknown error" : e.getMessage();
                            if ("Unsupported import format".equals(message)) {
                                MessageUtils.send(player, Lang.IMPORT_UNSUPPORTED_FORMAT);
                            } else if ("Import file not found".equals(message)) {
                                MessageUtils.send(player, Lang.IMPORT_FILE_NOT_FOUND, "{filename}", trimmedName);
                                MessageUtils.send(player, Lang.IMPORT_FILE_LOCATION_HINT, "{path}", MusicBox.getInstance().getDataFolder().getPath());
                            } else {
                                MessageUtils.send(player, Lang.IMPORT_FAILED, "{error}", message);
                            }
                            MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to import music file: " + message, e);
                        });
                    }
                });
            });
        });
    }

    private void startExportFlow() {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_MUSIC);
            return;
        }

        if (musicList.size() == 1) {
            exportMusic(musicList.get(0));
            return;
        }

        new MusicSelectGUI(player, MusicSelectGUI.SelectMode.EXPORT).open();
    }

    private void exportMusic(PlayerMusic music) {
        AsyncTaskManager.runAsync(() -> {
            try {
                NBSExporter.ExportResult result = NBSExporter.getInstance().export(music);
                Scheduler.entity(player, () -> {
                    MessageUtils.send(player, Lang.EXPORT_SUCCESS,
                            "{name}", music.getName(),
                            "{file}", result.file().getName(),
                            "{path}", result.file().getPath());
                    if (result.warnings() != null && result.warnings().stream().anyMatch(w -> w.startsWith("pitch_clamped"))) {
                        MessageUtils.send(player, Lang.EXPORT_WARNING_PITCH_CLAMPED);
                    }
                });
            } catch (Exception e) {
                Scheduler.entity(player,
                        () -> MessageUtils.send(player, Lang.EXPORT_FAILED, "{error}", e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        });
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    private void sendImportWarnings(ImportResult result) {
        if (result.warnings() == null || result.warnings().isEmpty()) {
            return;
        }
        for (String warning : result.warnings()) {
            if ("tempo_changes_collapsed".equals(warning)) {
                MessageUtils.send(player, Lang.IMPORT_WARNING_TEMPO_CHANGES);
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
