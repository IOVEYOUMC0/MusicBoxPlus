package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.GUIUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.core.playback.PlaybackContext;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.PlayerClickAction;
import com.huidu.musicboxplus.module.gui.playlist.PlayListEditorGUI;
import com.huidu.musicboxplus.module.gui.playlist.PlayListListGUI;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

// Builds the shared control-bar buttons. Each button resolves its item from the GUI config
// first and only falls back to a hardcoded ItemStack when no config entry exists, so a server
// can restyle any button without a code change.
public final class ButtonFactory {
    private ButtonFactory() {
    }

    private static ItemStack getButton(String name) {
        return GUIConfigManager.getInstance().createButtonItem("song-list", name);
    }

    private static ItemStack getMainMenuButton(String name, String... replacements) {
        GUIConfigManager guiConfig = GUIConfigManager.getInstance();
        if (guiConfig == null) {
            return null;
        }
        ItemStack item = guiConfig.createButtonItem("song-list", name, replacements);
        if (item == null) {
            item = guiConfig.createButtonItem("main-menu", name, replacements);
        }
        return item;
    }

    private static ItemStack getFirstMainMenuButton(String[] names, String... replacements) {
        for (String name : names) {
            ItemStack item = ButtonFactory.getMainMenuButton(name, replacements);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private static String getToggleText(boolean enabled) {
        GUIConfigManager.ToggleStatusConfig toggleStatusConfig = GUIConfigManager.getInstance().getToggleStatusConfig();
        return enabled ? toggleStatusConfig.getEnabled() : toggleStatusConfig.getDisabled();
    }

    private static String getPlaybackStatusText(boolean playing) {
        GUIConfigManager.PlaybackStatusConfig playbackStatusConfig = GUIConfigManager.getInstance().getPlaybackStatusConfig();
        return playing ? playbackStatusConfig.getPlaying() : playbackStatusConfig.getPaused();
    }

    // Shared with the control-panel renderers: three identical copies of this used to feed
    // the same screen, so a format change showed two different speed strings at once.
    public static String formatSpeed(float speed) {
        String formatted = String.format(java.util.Locale.ROOT, "%.2f", speed);
        while (formatted.contains(".") && (formatted.endsWith("0") || formatted.endsWith("."))) {
            formatted = formatted.substring(0, formatted.length() - 1);
        }
        return formatted + "x";
    }

    public static SongContainerGUI.BarButton createPlayModeButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                if (wrapper == null) {
                    return null;
                }
                boolean isSilent = wrapper.isSilent();
                boolean isSpeaker = wrapper.isSpeaker();
                String silentStatus = ButtonFactory.getToggleText(isSilent);
                String speakerStatus = ButtonFactory.getToggleText(isSpeaker);
                String[] candidateKeys = isSilent
                    ? new String[]{"play-mode-silent", "play-mode"}
                    : (isSpeaker ? new String[]{"play-mode-speaker", "play-mode"} : new String[]{"play-mode-normal", "play-mode"});
                ItemStack item = ButtonFactory.getFirstMainMenuButton(candidateKeys, "{silent}", silentStatus, "{speaker}", speakerStatus);
                if (item == null) {
                    Material material;
                    ItemStack fallbackItem;
                    if (isSilent) {
                        material = Material.GRAY_DYE;
                    } else if (isSpeaker) {
                        material = Material.JUKEBOX;
                    } else {
                        material = Material.NOTE_BLOCK;
                    }
                    fallbackItem = ItemUtils.createStack(material, "<gold>Play Mode", null);
                    item = fallbackItem;
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                if (wrapper == null || wrapper.getPlayer() == null) return null;
                return new ClickAction(() -> {
                    wrapper.setSilent(!wrapper.isSilent());
                    data.refreshInventory();
                }, () -> {
                    if (wrapper.canSwitch()) {
                        wrapper.switchModeChecked();
                        data.refreshInventory();
                    }
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createStopButton() {
        return new SongContainerGUI.BarButton(){
            private final ItemStack stopItem = GUIActions.getStopStack();

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                if (wrapper == null) return null;
                return wrapper.isPlayNow() ? this.stopItem : null;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    wrapper.destroyActivePlayer();
                    data.refreshInventory();
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createVolumeButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                if (wrapper == null || wrapper.getPlayer() == null) return null;
                int volume = VolumeManager.getPlayerVolume(wrapper.getPlayer());
                Material material = GUIUtils.getVolumeMaterial(volume);
                String[] candidateKeys = volume == 0
                    ? new String[]{"volume-level-muted", "volume-level"}
                    : (volume <= 25
                        ? new String[]{"volume-level-low", "volume-level"}
                        : (volume <= 50
                            ? new String[]{"volume-level-medium", "volume-level"}
                            : (volume <= 75 ? new String[]{"volume-level-high", "volume-level"} : new String[]{"volume-level-full", "volume-level"})));
                ItemStack item = ButtonFactory.getFirstMainMenuButton(candidateKeys, "{volume}", String.valueOf(volume));
                if (item == null) {
                    item = ItemUtils.createStack(material, "<gold>Volume", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                if (wrapper == null || wrapper.getPlayer() == null) return null;
                return new ClickAction(() -> {
                    VolumeManager.getInstance().increaseVolume(wrapper.getPlayer());
                    data.refreshInventory();
                }, () -> {
                    VolumeManager.getInstance().decreaseVolume(wrapper.getPlayer());
                    data.refreshInventory();
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createSpeedButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                String speedText = ButtonFactory.formatSpeed(wrapper.getPlaybackSpeedMultiplier());
                String stepText = ButtonFactory.formatSpeed(com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getSpeed().getStep());
                ItemStack item = ButtonFactory.getFirstMainMenuButton(new String[]{"speed-control"}, "{speed}", speedText, "{step}", stepText);
                if (item == null) {
                    item = ItemUtils.createStack(Material.CLOCK, Lang.SPEED_BUTTON.toString(), Lang.SPEED_BUTTON_LORE.toList("{speed}", speedText, "{step}", stepText));
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    float step = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getSpeed().getStep();
                    wrapper.setPlaybackSpeedMultiplier(wrapper.getPlaybackSpeedMultiplier() + step);
                    data.refreshInventory();
                }, () -> {
                    float step = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getSpeed().getStep();
                    wrapper.setPlaybackSpeedMultiplier(wrapper.getPlaybackSpeedMultiplier() - step);
                    data.refreshInventory();
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createSearchButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                ItemStack item = ButtonFactory.getButton("search");
                if (item == null) {
                    GUIConfigManager guiConfig = GUIConfigManager.getInstance();
                    item = ItemUtils.createStack(Material.COMPASS, guiConfig.getSearchConfig().getTitle(), null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> GUIInputManager.getInstance().requestSearchInput(wrapper));
            }
        };
    }

    public static SongContainerGUI.BarButton createPlaylistButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                ItemStack item = ButtonFactory.getButton("playlist");
                if (item == null) {
                    item = ItemUtils.createStack(Material.CHEST, "<gold>Playlist", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new PlayerClickAction(p -> PlayListListGUI.openAsync(wrapper, container -> new ClickAction(() -> wrapper.play(container), () -> {
                    if (container instanceof PlayerPlayListModel) {
                        new PlayListEditorGUI(wrapper, (PlayerPlayListModel)container).openPage(0);
                    }
                }), pl -> GUIConfigManager.getInstance().getPlaylistItemConfig().getListLore()));
            }
        };
    }

    public static SongContainerGUI.BarButton createControlPanelButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                PlaybackContext context = wrapper.getPlaybackContext();
                if (context == null) {
                    return null;
                }
                boolean isPlaying = context.isPlaying();
                String statusText = ButtonFactory.getPlaybackStatusText(isPlaying);
                String[] candidateKeys = isPlaying
                    ? new String[]{"control-panel-playing", "control-panel"}
                    : new String[]{"control-panel-paused", "control-panel"};
                ItemStack item = ButtonFactory.getFirstMainMenuButton(candidateKeys, "{status}", statusText);
                if (item == null) {
                    Material material = isPlaying ? Material.REDSTONE_TORCH : Material.LEVER;
                    item = ItemUtils.createStack(material, "<gold>Control Panel", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    PlaybackContext context = wrapper.getPlaybackContext();
                    if (context != null && context.getPlayer() instanceof PlayerSongPlayer active) {
                        active.getControl().open(wrapper.getPlayer());
                    }
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createSwitchModeButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                boolean isSpeaker = wrapper.isSpeaker();
                String statusText = ButtonFactory.getToggleText(isSpeaker);
                String[] candidateKeys = wrapper.canSwitch()
                    ? (isSpeaker ? new String[]{"speaker-mode-on", "speaker-mode"} : new String[]{"speaker-mode-off", "speaker-mode"})
                    : new String[]{"speaker-mode-no-permission", "speaker-mode"};
                ItemStack item = ButtonFactory.getFirstMainMenuButton(candidateKeys, "{status}", statusText);
                if (item == null) {
                    item = ItemUtils.createStack(Material.JUKEBOX, "<gold>Speaker Mode", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    if (wrapper.switchModeChecked()) {
                        data.refreshInventory();
                    }
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createLoopModeButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                PlaybackContext context = wrapper.getPlaybackContext();
                if (context == null) {
                    return null;
                }
                LoopMode loopMode = context.getLoopMode();
                String buttonKey = "loop-mode-" + loopMode.name().toLowerCase();
                GUIConfigManager.LoopModeConfig loopModeConfig = GUIConfigManager.getInstance().getLoopModeConfig();
                String modeText;
                switch (loopMode) {
                    case SINGLE: modeText = loopModeConfig.getSingle(); break;
                    case ALL: modeText = loopModeConfig.getAll(); break;
                    default: modeText = loopModeConfig.getOff(); break;
                }
                ItemStack item = ButtonFactory.getMainMenuButton(buttonKey, "{mode}", modeText);
                if (item == null) {
                    item = ButtonFactory.getMainMenuButton("loop-mode", "{mode}", modeText);
                }
                if (item == null) {
                    Material material = loopMode == LoopMode.SINGLE ? Material.REPEATER : (loopMode == LoopMode.ALL ? Material.COMPARATOR : Material.REDSTONE);
                    item = ItemUtils.createStack(material, "<gold>Loop Mode", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    PlaybackContext context = wrapper.getPlaybackContext();
                    if (context != null && context.getPlayer() != null && context.supportsLoopControl()) {
                        LoopMode newMode = context.getPlayer().toggleLoopMode();
                        wrapper.setLoopMode(newMode);
                        data.refreshInventory();
                    }
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createRecentSongsButton() {
        return ButtonFactory.createRecentSongsButton(false, null);
    }

    public static SongContainerGUI.BarButton createRecentSongsButton(final boolean buyMode, final Runnable backAction) {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                List<MusicBoxSong> recentSongs = wrapper.getRecentSongs();
                if (recentSongs.isEmpty()) {
                    return null;
                }
                ItemStack item = ButtonFactory.getMainMenuButton("recent-songs", "{count}", String.valueOf(recentSongs.size()));
                if (item == null) {
                    item = ItemUtils.createStack(Material.CLOCK, "<gold>Recent Songs", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    List<MusicBoxSong> recentSongs = wrapper.getRecentSongs();
                    if (!recentSongs.isEmpty()) {
                        Runnable resolvedBackAction = backAction != null ? backAction : (buyMode ? () -> GUIActions.openShopInventory(wrapper) : null);
                        new RecentSongsGUI(wrapper, buyMode, resolvedBackAction).openPage(0);
                    }
                });
            }
        };
    }

    public static SongContainerGUI.BarButton createPlayPauseButton() {
        return new SongContainerGUI.BarButton(){

            @Override
            public ItemStack getItemStack(PlayerWrapper wrapper) {
                PlaybackContext context = wrapper.getPlaybackContext();
                if (context == null) {
                    return null;
                }
                boolean isPlaying = context.isPlaying();
                ItemStack item = ButtonFactory.getMainMenuButton(isPlaying ? "pause" : "play");
                if (item == null) {
                    Material material = isPlaying ? Material.REDSTONE_BLOCK : Material.EMERALD_BLOCK;
                    item = ItemUtils.createStack(material, isPlaying ? "<gold>Pause" : "<gold>Play", null);
                }
                return item;
            }

            @Override
            public InventoryAction getAction(PlayerWrapper wrapper, SongContainerGUI.SongGUIData<Void> data) {
                return new ClickAction(() -> {
                    PlaybackContext context = wrapper.getPlaybackContext();
                    if (context != null && context.getPlayer() != null && context.supportsPauseResume()) {
                        if (context.isPlaying()) {
                            context.getPlayer().pause();
                        } else {
                            context.getPlayer().resume();
                        }
                        data.refreshInventory();
                    }
                });
            }
        };
    }
}
