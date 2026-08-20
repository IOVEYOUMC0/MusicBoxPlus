package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BPMSettingsGUI implements InventoryHolder {

    private final PlayerMusic music;
    private final Player player;
    private final SettingsMenuGUI parentGUI;
    private final Inventory inventory;
    private final GUIConfigManager.BPMSettingsConfig config;
    private boolean handledClose = false;

    private static final int[] BPM_STEPS = {-50, -20, -10, -5, -1, 1, 5, 10, 20, 50};

    private int getMaxBpm() {
        return MusicBox.getInstance().getConfigObject().getEditor().getMaxBpm();
    }

    private int getMinBpm() {
        return MusicBox.getInstance().getConfigObject().getEditor().getMinBpm();
    }

    public BPMSettingsGUI(PlayerMusic music, Player player, SettingsMenuGUI parentGUI) {
        this.music = music;
        this.player = player;
        this.parentGUI = parentGUI;
        this.config = GUIConfigManager.getInstance().getBPMSettingsConfig();
        String title = config.getTitle().replace("{name}", music.getName());
        this.inventory = Bukkit.createInventory(this, 36, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();
        List<Integer> stepSlots = config.getSlotsForChars("0123456789");
        if (stepSlots.isEmpty()) {
            stepSlots = new ArrayList<>();
            for (int i = 0; i < BPM_STEPS.length; i++) {
                stepSlots.add(i);
            }
        }

        int currentSlot = config.getSlotForButton("current-bpm");
        if (currentSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig currentConfig = config.getButton("current-bpm");
            if (currentConfig != null) {
                List<String> lore = new ArrayList<>();
                for (String line : currentConfig.getLore()) {
                    lore.add(line.replace("{bpm}", String.valueOf(music.getBpm())));
                }
                ItemStack currentBpmItem = ItemUtils.createStack(currentConfig.getMaterial(), currentConfig.getName().replace("{bpm}", String.valueOf(music.getBpm())), lore, currentConfig.getCustomModelData());
                inventory.setItem(currentSlot, currentBpmItem);
            }
        }

        for (int i = 0; i < BPM_STEPS.length && i < stepSlots.size(); i++) {
            int step = BPM_STEPS[i];
            ItemStack stepItem = createBpmStepItem(step);
            inventory.setItem(stepSlots.get(i), stepItem);
        }

        int resetSlot = config.getSlotForButton("reset");
        if (resetSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig resetConfig = config.getButton("reset");
            if (resetConfig != null) {
                ItemStack resetButton = resetConfig.createItem();
                inventory.setItem(resetSlot, resetButton);
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

    private ItemStack createBpmStepItem(int step) {
        Material material;
        int absStep = Math.abs(step);
        
        if (absStep >= 50) {
            material = Material.RED_STAINED_GLASS_PANE;
        } else if (absStep >= 20) {
            material = Material.ORANGE_STAINED_GLASS_PANE;
        } else if (absStep >= 10) {
            material = Material.YELLOW_STAINED_GLASS_PANE;
        } else if (absStep >= 5) {
            material = Material.GREEN_STAINED_GLASS_PANE;
        } else {
            material = Material.BLUE_STAINED_GLASS_PANE;
        }

        int newBpm = Math.max(getMinBpm(), Math.min(getMaxBpm(), music.getBpm() + step));
        String nameTemplate = step > 0 ? config.getStepIncreaseName() : config.getStepDecreaseName();
        List<String> lore = new ArrayList<>();
        for (String line : config.getStepLore()) {
            lore.add(line
                .replace("{amount}", String.valueOf(absStep))
                .replace("{current}", String.valueOf(music.getBpm()))
                .replace("{after}", String.valueOf(newBpm)));
        }

        return ItemUtils.createStack(
            material,
            nameTemplate.replace("{amount}", String.valueOf(absStep)),
            lore
        );
    }

    public void handleClick(int slot) {
        int backSlot = config.getSlotForButton("back");
        int resetSlot = config.getSlotForButton("reset");

        if (slot == backSlot) {
            handledClose = true;
            goBack();
            return;
        }
        
        if (slot == resetSlot) {
            music.setBpm(MusicBox.getInstance().getConfigObject().getEditor().getDefaultBpm());
            saveAndUpdate();
            return;
        }

        List<Integer> stepSlots = config.getSlotsForChars("0123456789");
        if (stepSlots.isEmpty()) {
            stepSlots = new ArrayList<>();
            for (int i = 0; i < BPM_STEPS.length; i++) {
                stepSlots.add(i);
            }
        }

        int stepIndex = stepSlots.indexOf(slot);
        if (stepIndex >= 0 && stepIndex < BPM_STEPS.length) {
            int step = BPM_STEPS[stepIndex];
            int newBpm = music.getBpm() + step;
            newBpm = Math.max(getMinBpm(), Math.min(getMaxBpm(), newBpm));
            music.setBpm(newBpm);
            saveAndUpdate();
        }
    }

    private void saveAndUpdate() {
        PlayerMusicManager.getInstance().saveMusicAsync(music, success -> {
            if (success) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                updateInventory();
            } else {
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            }
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

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
