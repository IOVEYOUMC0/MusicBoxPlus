package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.SoundUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.*;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusic;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class SettingsMenuGUI implements InventoryHolder {

    private final PlayerMusic music;
    private final Player player;
    private final MusicEditGUI parentGUI;
    private final Inventory inventory;
    private final GUIConfigManager.SettingsMenuConfig config;
    private boolean handledClose = false;

    public SettingsMenuGUI(PlayerMusic music, Player player, MusicEditGUI parentGUI) {
        this.music = music;
        this.player = player;
        this.parentGUI = parentGUI;
        this.config = GUIConfigManager.getInstance().getSettingsMenuConfig();
        String title = config.getTitle().replace("{name}", music.getName());
        this.inventory = Bukkit.createInventory(this, 27, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int bpmSlot = config.getSlotForButton("bpm");
        if (bpmSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig bpmConfig = config.getButton("bpm");
            if (bpmConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : bpmConfig.getLore()) {
                    lore.add(line.replace("{bpm}", String.valueOf(music.getBpm())));
                }
                ItemStack bpmButton = ItemUtils.createStack(bpmConfig.getMaterial(), bpmConfig.getName(), lore, bpmConfig.getCustomModelData());
                inventory.setItem(bpmSlot, bpmButton);
            }
        }

        int subdivisionSlot = config.getSlotForButton("subdivision");
        if (subdivisionSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig subConfig = config.getButton("subdivision");
            if (subConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : subConfig.getLore()) {
                    lore.add(line.replace("{subdivision}", String.valueOf(music.getBeatSubdivision())));
                }
                ItemStack subdivisionButton = ItemUtils.createStack(subConfig.getMaterial(), subConfig.getName(), lore, subConfig.getCustomModelData());
                inventory.setItem(subdivisionSlot, subdivisionButton);
            }
        }

        int timeSignatureSlot = config.getSlotForButton("time-signature");
        if (timeSignatureSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig tsConfig = config.getButton("time-signature");
            if (tsConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : tsConfig.getLore()) {
                    lore.add(line.replace("{timeSignature}", music.getTimeSignature().toString()));
                }
                ItemStack timeSignatureButton = ItemUtils.createStack(tsConfig.getMaterial(), tsConfig.getName(), lore, tsConfig.getCustomModelData());
                inventory.setItem(timeSignatureSlot, timeSignatureButton);
            }
        }

        int nameSlot = config.getSlotForButton("name");
        if (nameSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig nameConfig = config.getButton("name");
            if (nameConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : nameConfig.getLore()) {
                    lore.add(line.replace("{name}", music.getName()));
                }
                ItemStack nameButton = ItemUtils.createStack(nameConfig.getMaterial(), nameConfig.getName(), lore, nameConfig.getCustomModelData());
                inventory.setItem(nameSlot, nameButton);
            }
        }

        int clearSlot = config.getSlotForButton("clear");
        if (clearSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig clearConfig = config.getButton("clear");
            if (clearConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : clearConfig.getLore()) {
                    lore.add(line.replace("{notes}", String.valueOf(music.getNoteCount())));
                }
                ItemStack clearButton = ItemUtils.createStack(clearConfig.getMaterial(), clearConfig.getName(), lore, clearConfig.getCustomModelData());
                inventory.setItem(clearSlot, clearButton);
            }
        }

        int saveCloseSlot = config.getSlotForButton("save-close");
        if (saveCloseSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig saveConfig = config.getButton("save-close");
            if (saveConfig != null) {
                ItemStack saveCloseButton = saveConfig.createItem();
                inventory.setItem(saveCloseSlot, saveCloseButton);
            }
        }

        int publishSlot = config.getSlotForButton("publish");
        if (publishSlot >= 0 && MusicBox.getInstance().isPublishModuleEnabled() && player.hasPermission(Permissions.SHOPMUSIC)) {
            GUIConfigManager.HotbarButtonConfig publishConfig = config.getButton("publish");
            if (publishConfig != null) {
                PublishedMusic published = findPublishedMusic();
                String status = published == null
                        ? Lang.PUBLISH_ACTION_PUBLISH.toString()
                        : published.isAvailable() ? Lang.PUBLISH_STATUS_PUBLISHED.toString() : Lang.PUBLISH_STATUS_UNPUBLISHED.toString();
                String action = published == null
                        ? Lang.PUBLISH_ACTION_PUBLISH.toString()
                        : Lang.PUBLISH_ACTION_MANAGE.toString();
                List<String> lore = new ArrayList<>();
                for (String line : publishConfig.getLore()) {
                    lore.add(line
                            .replace("{status}", status)
                            .replace("{action}", action));
                }
                String name = publishConfig.getName()
                        .replace("{status}", status)
                        .replace("{action}", action);
                ItemStack publishButton = ItemUtils.createStack(publishConfig.getMaterial(), name, lore, publishConfig.getCustomModelData());
                inventory.setItem(publishSlot, publishButton);
            }
        }

        int backSlot = config.getSlotForButton("back");
        if (backSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig backConfig = config.getButton("back");
            if (backConfig != null) {
                ItemStack backButton = backConfig.createItem();
                inventory.setItem(backSlot, backButton);
            }
        }
    }

    public void handleClick(int slot) {
        int bpmSlot = config.getSlotForButton("bpm");
        int subdivisionSlot = config.getSlotForButton("subdivision");
        int timeSignatureSlot = config.getSlotForButton("time-signature");
        int nameSlot = config.getSlotForButton("name");
        int clearSlot = config.getSlotForButton("clear");
        int saveCloseSlot = config.getSlotForButton("save-close");
        int publishSlot = config.getSlotForButton("publish");
        int backSlot = config.getSlotForButton("back");

        if (slot == bpmSlot) {
            handledClose = true;
            openBPMSettings();
        } else if (slot == subdivisionSlot) {
            cycleSubdivision();
        } else if (slot == timeSignatureSlot) {
            cycleTimeSignature();
        } else if (slot == nameSlot) {
            startNameEdit();
        } else if (slot == clearSlot) {
            clearNotes();
        } else if (slot == saveCloseSlot) {
            handledClose = true;
            saveAndClose();
        } else if (slot == publishSlot && MusicBox.getInstance().isPublishModuleEnabled() && player.hasPermission(Permissions.SHOPMUSIC)) {
            handledClose = true;
            openPublishGUI();
        } else if (slot == backSlot) {
            handledClose = true;
            goBack();
        }
    }

    private void openBPMSettings() {
        parentGUI.setSubGuiOpen(true);
        player.closeInventory();
        BPMSettingsGUI bpmGUI = new BPMSettingsGUI(music, player, this);
        bpmGUI.open();
        Scheduler.globalLater(() -> parentGUI.setSubGuiOpen(false), 2L);
    }

    private void cycleSubdivision() {
        int current = music.getBeatSubdivision();
        int newSubdivision = current >= 16 ? 1 : current + 1;
        music.setBeatSubdivision(newSubdivision);
        parentGUI.setHasUnsavedChanges(true);
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                parentGUI.setHasUnsavedChanges(false);
            }
        });
        SoundUtils.playClickSound(player);
        updateInventory();
    }

    private void cycleTimeSignature() {
        PlayerMusic.TimeSignature[] signatures = PlayerMusic.TimeSignature.values();
        int currentIndex = 0;
        for (int i = 0; i < signatures.length; i++) {
            if (signatures[i] == music.getTimeSignature()) {
                currentIndex = i;
                break;
            }
        }
        PlayerMusic.TimeSignature newSignature = signatures[(currentIndex + 1) % signatures.length];
        music.setTimeSignature(newSignature);
        parentGUI.setHasUnsavedChanges(true);
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
            if (!success) {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            } else {
                parentGUI.setHasUnsavedChanges(false);
            }
        });
        SoundUtils.playClickSound(player);
        updateInventory();
    }

    private void startNameEdit() {
        handledClose = true;
        parentGUI.setSubGuiOpen(true);
        player.closeInventory();
        MusicEditGUI.startTextInput(player, music, "name", null, () -> {
            parentGUI.setSubGuiOpen(false);
            open();
        });
    }

    private void clearNotes() {
        int count = music.getNoteCount();
        if (count == 0) {
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_CLEAR);
            return;
        }
        music.clearNotes();
        parentGUI.setHasUnsavedChanges(true);
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
            if (success) {
                Scheduler.entity(player, () -> {
                    parentGUI.setHasUnsavedChanges(false);
                    SoundUtils.playSound(player, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f);
                    MessageUtils.send(player, Lang.EDIT_NOTES_CLEARED, "{count}", String.valueOf(count));
                    updateInventory();
                });
            } else {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            }
        });
    }

    private void saveAndClose() {
        SoundUtils.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 2.0f);
        parentGUI.setSubGuiOpen(true);
        parentGUI.closeAndSave();
    }

    private void openPublishGUI() {
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
            if (!success) {
                Scheduler.entity(player, () -> {
                    MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
                    open();
                });
                return;
            }
            parentGUI.stopAutoSaveTask();
            parentGUI.setSubGuiOpen(true);
            MusicEditListener.exitEditMode(music.getUniqueId());
            MusicEditListener.removeOpenGUI(player.getUniqueId());
            Scheduler.entity(player, () -> {
                player.closeInventory();
                parentGUI.setSubGuiOpen(false);
                PlayerInventoryState.restoreAndRemove(player);
                new PublishGUI(player).open();
            });
        });
    }

    private void goBack() {
        parentGUI.open();
    }

    public void handleClose() {
        if (handledClose || !player.isOnline()) {
            return;
        }
        handledClose = true;
        parentGUI.open();
    }

    private PublishedMusic findPublishedMusic() {
        return PublishedMusicManager.getInstance().getPublishedByAuthor(player.getUniqueId()).stream()
                .filter(published -> music.getUniqueId().equals(published.getOriginalMusicId()))
                .findFirst()
                .orElse(null);
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public PlayerMusic getMusic() {
        return music;
    }

    public MusicEditGUI getParentGUI() {
        return parentGUI;
    }
}
