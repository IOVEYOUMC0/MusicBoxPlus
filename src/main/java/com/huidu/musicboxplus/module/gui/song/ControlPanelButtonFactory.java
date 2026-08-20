package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.module.gui.ButtonFactory;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.core.playback.PlaybackContext;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class ControlPanelButtonFactory {
    private ControlPanelButtonFactory() {
    }

    static ItemStack createProtectButton(GUIConfigManager configManager, MusicBoxSongPlayer player, Player viewer) {
        if (!(player instanceof SignPlayer)) {
            return null;
        }
        SignPlayer signPlayer = (SignPlayer) player;
        if (!signPlayer.canToggleProtect(viewer)) {
            return null;
        }
        boolean isPrevented = signPlayer.isPreventDestroy();
        String statusText = isPrevented ? Lang.ENABLE.toString() : Lang.DISABLE.toString();

        ItemStack item = configManager.createButtonItem("sign-setup", "prevent-destroy", "{status}", statusText);
        if (item == null) {
            String buttonKey = isPrevented ? "protect-enabled" : "protect-disabled";
            item = configManager.createButtonItem("control-panel", buttonKey);
        }
        if (item != null) {
            return item;
        }

        String detailedStatusText = isPrevented ? Lang.CONTROL_PROTECT_ENABLED.toString() : Lang.CONTROL_PROTECT_DISABLED.toString();
        return ItemUtils.createStack(
            isPrevented ? Material.SHIELD : Material.LEATHER,
            detailedStatusText,
            Arrays.asList(
                Lang.CONTROL_PROTECT_STATUS.toString("{status}", detailedStatusText),
                Lang.CONTROL_PROTECT_CLICK.toString(),
                isPrevented ? Lang.CONTROL_PROTECT_CLOSE_HINT.toString() : Lang.CONTROL_PROTECT_OPEN_HINT.toString()
            )
        );
    }

    static ItemStack createPlayPauseButton(GUIConfigManager configManager, PlaybackContext context) {
        if (context != null && !context.supportsPauseResume()) {
            return getConfiguredControlButton(
                configManager,
                "playback-unavailable-pause",
                ItemUtils.createStack(Material.GRAY_DYE, "<gray>Unavailable", List.of("<gray>Pause is not available for vanilla record playback"))
            );
        }
        boolean isPlaying = context != null && context.isPlaying();
        String buttonKey = isPlaying ? "pause" : "play";
        return getConfiguredControlButton(
            configManager,
            buttonKey,
            isPlaying
                ? ItemUtils.createStack(Material.REDSTONE_BLOCK, "<red>Pause", List.of("<gray>Click to pause playback"))
                : ItemUtils.createStack(Material.EMERALD_BLOCK, "<green>Play", List.of("<gray>Click to resume playback"))
        );
    }

    static ItemStack createLoopButton(GUIConfigManager configManager, PlaybackContext context, boolean isJukebox) {
        if (context != null && !context.supportsLoopControl()) {
            return getConfiguredControlButton(
                configManager,
                "playback-unavailable-loop",
                ItemUtils.createStack(Material.GRAY_DYE, "<gray>Unavailable", List.of("<gray>Loop mode is not available for vanilla record playback"))
            );
        }
        LoopMode loopMode = context != null ? context.getLoopMode() : null;
        if (loopMode == null) {
            loopMode = LoopMode.OFF;
        }
        if (isJukebox && loopMode == LoopMode.ALL) {
            loopMode = LoopMode.OFF;
        }
        GUIConfigManager.LoopModeConfig loopModeConfig = configManager.getLoopModeConfig();
        String modeText;
        switch (loopMode) {
            case SINGLE:
                modeText = loopModeConfig.getSingle();
                break;
            case ALL:
                modeText = loopModeConfig.getAll();
                break;
            default:
                modeText = loopModeConfig.getOff();
                break;
        }
        String buttonKey = "loop-mode-" + loopMode.name().toLowerCase(Locale.ROOT);
        ItemStack item = configManager.createButtonItem("control-panel", buttonKey, "{mode}", modeText);
        if (item == null) {
            item = configManager.createButtonItem("control-panel", "loop-mode", "{mode}", modeText);
        }
        if (item == null) {
            Material material = loopMode == LoopMode.SINGLE ? Material.REPEATER : (loopMode == LoopMode.ALL && !isJukebox ? Material.COMPARATOR : Material.REDSTONE);
            item = ItemUtils.createStack(material, "<gold>Loop Mode: " + modeText, List.of("<gray>Click to change loop mode"));
        }
        return item;
    }

    static ItemStack createVolumeButton(GUIConfigManager configManager, MusicBoxSongPlayer currentPlayer, Player viewer) {
        int currentVolume = getCurrentVolume(currentPlayer, viewer);
        GUIConfigManager.VolumeControlConfig volumeConfig = configManager.getVolumeControlConfig();
        Material volumeMaterial;
        int customModelData;
        if (currentVolume == 0) {
            volumeMaterial = volumeConfig.getMaterialMute() != null ? volumeConfig.getMaterialMute() : Material.BARRIER;
            customModelData = volumeConfig.getCustomModelDataMute();
        } else if (currentVolume <= 25) {
            volumeMaterial = volumeConfig.getMaterialLow() != null ? volumeConfig.getMaterialLow() : Material.RED_WOOL;
            customModelData = volumeConfig.getCustomModelDataLow();
        } else if (currentVolume <= 50) {
            volumeMaterial = volumeConfig.getMaterialMedium() != null ? volumeConfig.getMaterialMedium() : Material.ORANGE_WOOL;
            customModelData = volumeConfig.getCustomModelDataMedium();
        } else if (currentVolume <= 75) {
            volumeMaterial = volumeConfig.getMaterialHigh() != null ? volumeConfig.getMaterialHigh() : Material.YELLOW_WOOL;
            customModelData = volumeConfig.getCustomModelDataHigh();
        } else {
            volumeMaterial = volumeConfig.getMaterialFull() != null ? volumeConfig.getMaterialFull() : Material.GREEN_WOOL;
            customModelData = volumeConfig.getCustomModelDataFull();
        }
        String[] candidateKeys = currentVolume == 0
            ? new String[]{"volume-control-muted", "volume-control"}
            : (currentVolume <= 25
            ? new String[]{"volume-control-low", "volume-control"}
            : (currentVolume <= 50
            ? new String[]{"volume-control-medium", "volume-control"}
            : (currentVolume <= 75
            ? new String[]{"volume-control-high", "volume-control"}
            : new String[]{"volume-control-full", "volume-control"})));
        ItemStack item = null;
        for (String key : candidateKeys) {
            item = configManager.createButtonItem("control-panel", key, "{volume}", String.valueOf(currentVolume));
            if (item != null) {
                break;
            }
        }
        if (item == null) {
            item = ItemUtils.createStack(volumeMaterial, "<gold>Volume Control", Arrays.asList("<yellow>Volume: " + currentVolume + "%", "", "<green>Left Click: Increase (+10%)", "<red>Right Click: Decrease (-10%)"), customModelData);
        }
        return item;
    }

    static ItemStack createSpeedButton(GUIConfigManager configManager, MusicBoxSongPlayer player) {
        float speed = getCurrentSpeedMultiplier(player);
        float step = MusicBox.getInstance().getConfigObject().getSpeed().getStep();
        ItemStack item = configManager.createButtonItem("control-panel", "speed-control", "{speed}", ButtonFactory.formatSpeed(speed), "{step}", ButtonFactory.formatSpeed(step));
        if (item == null) {
            item = ItemUtils.createStack(Material.CLOCK, Lang.SPEED_BUTTON.toString(), Lang.SPEED_BUTTON_LORE.toList("{speed}", ButtonFactory.formatSpeed(speed), "{step}", ButtonFactory.formatSpeed(step)));
        }
        return item;
    }

    private static ItemStack getConfiguredControlButton(GUIConfigManager configManager, String name, ItemStack fallback) {
        ItemStack item = configManager.createButtonItem("control-panel", name);
        return item != null ? item : fallback;
    }

    private static int getCurrentVolume(MusicBoxSongPlayer currentPlayer, Player viewer) {
        if (currentPlayer instanceof AbstractBlockPlayer) {
            return currentPlayer.getMusicBoxModel().getVolume();
        }
        if (currentPlayer instanceof PlayerSongPlayer && currentPlayer.getMusicBoxModel().getPositionPlayer() != null) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(currentPlayer);
            if (wrapper != null && wrapper.getPlayer() != null) {
                return VolumeManager.getPlayerVolume(wrapper.getPlayer());
            }
        }
        return viewer != null ? VolumeManager.getPlayerVolume(viewer) : 100;
    }

    private static float getCurrentSpeedMultiplier(MusicBoxSongPlayer player) {
        if (player instanceof AbstractBlockPlayer) {
            return player.getMusicBoxModel().getPlaybackSpeedMultiplier();
        }
        if (player instanceof PlayerSongPlayer) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(player);
            if (wrapper != null) {
                return wrapper.getPlaybackSpeedMultiplier();
            }
        }
        return 1.0f;
    }

}
