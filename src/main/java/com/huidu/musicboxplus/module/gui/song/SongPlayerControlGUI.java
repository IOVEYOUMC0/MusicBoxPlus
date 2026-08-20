package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerControlGUI;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlaybackContext;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import com.huidu.musicboxplus.core.player.models.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.PlayerClickAction;
import com.huidu.musicboxplus.module.gui.textplayer.TextDisplayPlayerEditGUI;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public class SongPlayerControlGUI implements PlayerControlGUI {
    private final MusicBoxSongPlayerModel spModel;
    private final GUI gui;
    private final GUIConfigManager configManager;
    private final PlayerWrapper playerWrapper;
    private final Location trackedBlockLocation;
    private MbTask updateTask;
    private int lastTick = -1;
    private Integer lastSongHash = null;
    private MusicBoxSongPlayer lastPlayerRef = null;
    private LayoutParser layoutParser;
    private Player viewer;

    private PlaybackContext getPlaybackContext() {
        return PlaybackContext.fromPlayer(this.getCurrentMusicPlayer());
    }

    private MusicBoxSongPlayer getCurrentMusicPlayer() {
        if (this.playerWrapper != null && this.playerWrapper.getActivePlayer() != null) {
            return this.playerWrapper.getActivePlayer();
        }
        if (this.trackedBlockLocation != null) {
            MusicBoxSongPlayer blockPlayer = AbstractBlockPlayer.findByLocation(this.trackedBlockLocation);
            if (blockPlayer != null) {
                return blockPlayer;
            }
        }
        MusicBoxSongPlayer player = this.spModel.getMusicBoxSongPlayer();
        if (player instanceof PlayerSongPlayer) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(player);
            if (wrapper != null && wrapper.getActivePlayer() != null) {
                return wrapper.getActivePlayer();
            }
        }
        return player;
    }

    public SongPlayerControlGUI(MusicBoxSongPlayerModel songPlayerModel) {
        this.spModel = songPlayerModel;
        this.configManager = GUIConfigManager.getInstance();
        MusicBoxSongPlayer initialPlayer = songPlayerModel.getMusicBoxSongPlayer();
        if (initialPlayer instanceof PlayerSongPlayer) {
            this.playerWrapper = GUIActions.playerWrapperOf(initialPlayer);
        } else {
            this.playerWrapper = null;
        }
        if (initialPlayer instanceof AbstractBlockPlayer) {
            this.trackedBlockLocation = ((AbstractBlockPlayer) initialPlayer).getLocation();
        } else {
            this.trackedBlockLocation = null;
        }
        String title = this.configManager.getGUITitle("control-panel");
        MusicBoxSong currentSong = songPlayerModel.getPlayList().getCurrent();
        String currentSongName = currentSong != null ? currentSong.getName() : Lang.NO_MUSIC_PLAYING.toString();
        if (title == null || title.isEmpty()) {
            title = Lang.CONTROL_PANEL_TITLE.toString("{song}", currentSongName);
        }
        title = title.replace("{song}", currentSongName);
        int rows = this.configManager.getGUIRows("control-panel");
        if (rows <= 0) {
            rows = 3;
        }
        this.gui = new GUI(title, rows);
        this.layoutParser = new LayoutParser(this.gui, "control-panel");
        // The update loop closes/refreshes the viewer's inventory, so it must run on the
        // viewer's region. It is started in open(Player) once the viewer is known.
    }

    private void initializeLayout() {
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("control-panel").getButtonMapping();
        this.layoutParser.registerButton(mapping.getPlayPause(), () -> ControlPanelButtonFactory.createPlayPauseButton(this.configManager, this.getPlaybackContext()), () -> new ClickAction(() -> {
            MusicBoxSongPlayer songPlayer = this.getCurrentMusicPlayer();
            if (songPlayer != null) {
                if (songPlayer.isPlaying()) {
                    songPlayer.pause();
                } else {
                    songPlayer.resume();
                }
                this.refresh();
            }
        }));
        
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        if (currentPlayer instanceof PlayerSongPlayer) {
            this.layoutParser.registerPlayerButton(mapping.getBack(), "back", p -> {
                this.stopUpdateTask();
                MusicBoxSongPlayer songPlayer = this.getCurrentMusicPlayer();
                if (songPlayer instanceof PlayerSongPlayer) {
                    PlayerSongPlayer psp = (PlayerSongPlayer) songPlayer;
                    GUIActions.openDefaultInventory(GUIActions.playerWrapperOf(psp));
                } else {
                    p.closeInventory();
                }
            });
        } else {
            this.layoutParser.registerPlayerButton(mapping.getBack(), "back", p -> {
                this.stopUpdateTask();
                p.closeInventory();
            });
        }
        
        if (currentPlayer instanceof SignPlayer) {
            this.layoutParser.registerButton(mapping.getStop(), () -> this.getConfiguredControlButton(
                "destroy",
                ItemUtils.createStack(Material.BARRIER, Lang.CONTROL_DESTROY_PLAYER.toString(), Lang.CONTROL_DESTROY_PLAYER_LORE.toList())
            ), () -> new PlayerClickAction(p -> this.handleDestroy(p)));
            
            this.layoutParser.registerButton(mapping.getProtect(), () -> ControlPanelButtonFactory.createProtectButton(this.configManager, this.getCurrentMusicPlayer(), this.viewer), () -> new PlayerClickAction(p -> this.handleTogglePreventDestroy(p)));
        } else if (currentPlayer instanceof JukeboxPlayer) {
            this.layoutParser.registerButton(mapping.getStop(), () -> this.getConfiguredControlButton(
                "stop",
                ItemUtils.createStack(Material.BARRIER, Lang.STOP_BUTTON.toString(), Lang.STOP_BUTTON_LORE.toList())
            ), () -> new PlayerClickAction(p -> this.handleStop(p)));
        } else if (currentPlayer instanceof TextDisplayPlayer) {
            this.layoutParser.registerButton(mapping.getProtect(), this::createTextDisplayEditButton, () -> new PlayerClickAction(p -> this.openTextDisplayEditor(p)));
        } else {
            this.layoutParser.registerButton(mapping.getStop(), () -> GUIActions.getStopStack(), () -> new PlayerClickAction(p -> this.handleStop(p)));
        }
        
        boolean canControlLoop = this.canControlLoopMode();
        boolean isJukebox = currentPlayer instanceof JukeboxPlayer;
        this.layoutParser.registerButton(mapping.getLoop(), () -> ControlPanelButtonFactory.createLoopButton(this.configManager, this.getPlaybackContext(), isJukebox), canControlLoop ? () -> new ClickAction(() -> {
            MusicBoxSongPlayer songPlayer = this.getCurrentMusicPlayer();
            if (songPlayer != null) {
                LoopMode newMode;
                if (songPlayer instanceof JukeboxPlayer) {
                    newMode = songPlayer.getLoopMode() == LoopMode.OFF ? LoopMode.SINGLE : LoopMode.OFF;
                    songPlayer.setLoopMode(newMode);
                } else {
                    newMode = songPlayer.toggleLoopMode();
                }
                this.syncLoopModeToWrapper(songPlayer, newMode);
                this.refresh();
            }
        }) : () -> null);
        this.layoutParser.registerButton(mapping.getVolume(), () -> ControlPanelButtonFactory.createVolumeButton(this.configManager, this.getCurrentMusicPlayer(), this.viewer), () -> new ClickAction(() -> this.adjustVolume(10), () -> this.adjustVolume(-10)));
        this.layoutParser.registerButton(mapping.getSpeed(), () -> ControlPanelButtonFactory.createSpeedButton(this.configManager, this.getCurrentMusicPlayer()), this.canControlSpeed() ? () -> new ClickAction(() -> this.adjustSpeed(MusicBox.getInstance().getConfigObject().getSpeed().getStep()), () -> this.adjustSpeed(-MusicBox.getInstance().getConfigObject().getSpeed().getStep())) : () -> null);
        String layout = this.configManager.getGUILayout("control-panel");
        if (layout != null && !layout.isEmpty()) {
            this.layoutParser.parseAndApply(layout);
        }
        this.refresh();
    }
    
    private void handleStop(Player p) {
        MusicBoxSongPlayer player = this.getCurrentMusicPlayer();
        if (player == null) {
            return;
        }
        // stop() fires MusicBoxStopEvent first; a listener may veto the stop.
        if (player.stop()) {
            return;
        }
        this.stopUpdateTask();
        player.destroy(DestroyReason.MANUAL_STOP);
        p.closeInventory();
    }

    private void handleDestroy(Player p) {
        MusicBoxSongPlayer player = this.getCurrentMusicPlayer();
        if (player == null) {
            return;
        }
        if (player instanceof SignPlayer) {
            SignPlayer signPlayer = (SignPlayer)player;
            if (signPlayer.isOwnerOrAdmin(p)) {
                this.stopUpdateTask();
                Location signLocation = signPlayer.getSign() != null ? signPlayer.getSign().getLocation() : null;
                player.destroy();
                if (signLocation != null) {
                    Scheduler.regionLater(signLocation, () -> {
                        Block block = signLocation.getBlock();
                        BlockState state = block.getState();
                        Sign sign = state instanceof Sign ? (Sign) state : null;
                        // Block state is read on the sign's region; player-inventory work
                        // (open/close) must run on the clicking player's own region.
                        Scheduler.entity(p, () -> {
                            if (sign != null) {
                                GUIActions.openSignSetupInventory(PlayerWrapper.getInstance(p), sign);
                            } else {
                                p.closeInventory();
                            }
                        });
                    }, 2L);
                } else {
                    p.closeInventory();
                }
            } else {
                MessageUtils.send(p, Lang.SIGN_NOT_OWNER);
            }
        }
    }

    private void handleTogglePreventDestroy(Player p) {
        MusicBoxSongPlayer player = this.getCurrentMusicPlayer();
        if (player instanceof SignPlayer) {
            SignPlayer signPlayer = (SignPlayer) player;
            if (signPlayer.canToggleProtect(p)) {
                signPlayer.togglePreventDestroy();
                this.refresh();
            } else {
                if (!p.hasPermission(Permissions.SIGN_PROTECT)) {
                    MessageUtils.send(p, Lang.NO_PERMISSIONS);
                } else {
                    MessageUtils.send(p, Lang.SIGN_NOT_OWNER);
                }
            }
        }
    }

    private boolean canControlSpeed() {
        PlaybackContext context = this.getPlaybackContext();
        return context != null && !context.isVanillaJukeboxPlayback();
    }

    private void adjustSpeed(float delta) {
        MusicBoxSongPlayer musicPlayer = this.getCurrentMusicPlayer();
        if (musicPlayer instanceof AbstractBlockPlayer) {
            AbstractBlockPlayer blockPlayer = (AbstractBlockPlayer) musicPlayer;
            blockPlayer.setStoredPlaybackSpeedMultiplier(blockPlayer.getMusicBoxModel().getPlaybackSpeedMultiplier() + delta);
            short currentTick = blockPlayer.getTick();
            boolean wasPlaying = blockPlayer.isPlaying();
            Location location = blockPlayer.getLocation();
            if (location == null) {
                return;
            }
            // createNextPlayer builds a new block player (block state access) -> run on the
            // block's region, then hop the GUI refresh back to the viewer's region.
            Scheduler.region(location, () -> {
                blockPlayer.getMusicBoxModel().createNextPlayer();
                AbstractBlockPlayer newPlayer = AbstractBlockPlayer.findByLocation(location);
                if (newPlayer != null && !newPlayer.isDestroyed()) {
                    newPlayer.setTick(currentTick);
                    if (!wasPlaying) {
                        newPlayer.pause();
                    }
                    if (this.viewer != null) {
                        Scheduler.entity(this.viewer, this::refresh);
                    }
                }
            });
            return;
        }
        if (musicPlayer instanceof PlayerSongPlayer) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(musicPlayer);
            if (wrapper != null) {
                wrapper.setPlaybackSpeedMultiplier(wrapper.getPlaybackSpeedMultiplier() + delta);
                this.refresh();
            }
        }
    }

    private float getEffectivePlaybackSpeed(MusicBoxSongPlayer musicPlayer) {
        if (musicPlayer == null || musicPlayer.getMusicBoxSong() == null) {
            return 1.0f;
        }
        float speed = ((com.huidu.musicboxplus.core.song.MusicBoxSong) musicPlayer.getMusicBoxSong()).getSpeed();
        if (musicPlayer instanceof AbstractBlockPlayer) {
            speed *= musicPlayer.getMusicBoxModel().getPlaybackSpeedMultiplier();
            return Math.max(0.1f, speed);
        }
        if (musicPlayer instanceof PlayerSongPlayer) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(musicPlayer);
            if (wrapper != null) {
                speed *= wrapper.getPlaybackSpeedMultiplier();
            }
        }
        return Math.max(0.1f, speed);
    }

    private void startUpdateTask() {
        if (this.viewer == null) {
            return;
        }
        this.stopUpdateTask();
        // Bound to the viewer: the loop closes the viewer's inventory and refreshes the GUI
        // it is looking at, so it runs on the viewer's region (main thread on regular Paper).
        this.updateTask = Scheduler.entityTimer(this.viewer, () -> {
            // Stop as soon as the viewer is no longer looking at this panel. Without it, a
            // panel that was closed keeps running and eventually calls closeInventory(), which
            // acts on whatever window the player has open by then -- so one player's song
            // ending shuts a different panel the player had opened since.
            //
            // Compares the inventory, not the holder: setTitle() rebuilds the Inventory while
            // keeping the same holder, so a holder comparison would miss the change.
            if (this.viewer == null || !this.viewer.isOnline() || this.gui == null
                    || this.viewer.getOpenInventory().getTopInventory() != this.gui.getInventory()) {
                stopUpdateTask();
                return;
            }
            MusicBoxSongPlayer player = this.getCurrentMusicPlayer();
            if (player == null || player.isDestroyed()) {
                if (player instanceof AbstractBlockPlayer) {
                    AbstractBlockPlayer blockPlayer = (AbstractBlockPlayer) player;
                    AbstractBlockPlayer newPlayer = AbstractBlockPlayer.findByLocation(blockPlayer.getLocation());
                    if (newPlayer != null && this.viewer != null) {
                        this.stopUpdateTask();
                        this.viewer.closeInventory();
                        Scheduler.entityLater(this.viewer, () -> {
                            newPlayer.getControl().open(this.viewer);
                        }, 1L);
                        return;
                    }
                }
                this.stopUpdateTask();
                if (this.viewer != null) {
                    this.viewer.closeInventory();
                }
                return;
            }
            MusicBoxSong currentSong = player.getMusicBoxSong();
            Integer currentSongHash = currentSong != null ? currentSong.getHash() : null;
            if (player != this.lastPlayerRef || !java.util.Objects.equals(currentSongHash, this.lastSongHash)) {
                this.lastPlayerRef = player;
                this.lastSongHash = currentSongHash;
                this.lastTick = player.getTick();
                this.refresh();
                return;
            }
            short currentTick = player.getTick();
            if (currentTick != this.lastTick) {
                this.lastTick = currentTick;
                this.updateProgressBar();
            }
        }, 10L, 10L);
    }

    public void stopUpdateTask() {
        if (this.updateTask != null) {
            this.updateTask.cancel();
            this.updateTask = null;
        }
    }

    public void refresh() {
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        this.lastPlayerRef = currentPlayer;
        MusicBoxSong currentSong = currentPlayer != null ? currentPlayer.getMusicBoxSong() : null;
        this.lastSongHash = currentSong != null ? currentSong.getHash() : null;
        this.lastTick = currentPlayer != null ? currentPlayer.getTick() : -1;
        this.updateTitle();
        this.updateSongList();
        this.updateProgressBar();
        this.updateControlButtons();
    }

    private void updateTitle() {
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        MusicBoxSong song = currentPlayer != null ? currentPlayer.getMusicBoxSong() : null;
        String songName = song != null ? song.getName() : Lang.NO_MUSIC_PLAYING.toString();
        String title = this.configManager.getGUITitle("control-panel");
        if (title == null || title.isEmpty()) {
            title = Lang.CONTROL_PANEL_TITLE.toString("{song}", songName);
        } else {
            title = title.replace("{song}", songName);
        }
        if (this.viewer == null || !this.viewer.isOnline()) {
            this.gui.setTitle(title);
            return;
        }
        InventoryView openInventory = this.viewer.getOpenInventory();
        if (openInventory != null && openInventory.getTopInventory().getHolder() == this.gui) {
            // Paper 1.21.4 exposes only the deprecated String overload on InventoryView; the
            // Component overload arrived later. Keep the legacy round-trip under a local
            // suppression instead of widening it to the whole class.
            setOpenInventoryTitle(openInventory, MiniMessageUtils.toLegacyText(MiniMessageUtils.processComponent(title)));
        } else {
            this.gui.setTitle(title);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setOpenInventoryTitle(InventoryView view, String legacyTitle) {
        view.setTitle(legacyTitle);
    }

    private void updateSongList() {
        IPlayList list = this.spModel.getPlayList();
        ControlPanelSongListRenderer.render(
            this.gui,
            this.layoutParser,
            this.configManager,
            list,
            this.canSwitchSong(),
            this::switchToSong
        );
    }

    private void switchToSong(MusicBoxSong song) {
        MusicBoxSongPlayer musicPlayer = this.getCurrentMusicPlayer();
        if (musicPlayer instanceof PlayerSongPlayer) {
            PlayerSongPlayer psp = (PlayerSongPlayer) musicPlayer;
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(psp);
            this.spModel.getPlayList().setSong(song);
            wrapper.play(this.spModel.getPlayList(), (short)0);
            this.refresh();
        } else if (musicPlayer instanceof SignPlayer) {
            this.spModel.getPlayList().setSong(song);
            Location signLoc = ((SignPlayer) musicPlayer).getSign().getLocation();
            // createNextPlayer rebuilds the sign player (block access) -> block region;
            // then reopen the control GUI on the viewer's region.
            Scheduler.region(signLoc, () -> {
                this.spModel.createNextPlayer();
                SignPlayer newPlayer = AbstractBlockPlayer.findByLocation(signLoc);
                if (newPlayer != null && this.viewer != null) {
                    Scheduler.entity(this.viewer, () -> {
                        this.stopUpdateTask();
                        this.viewer.closeInventory();
                        Scheduler.entityLater(this.viewer, () -> newPlayer.getControl().open(this.viewer), 2L);
                    });
                }
            });
        } else if (musicPlayer instanceof JukeboxPlayer) {
            Location jukeboxLoc = ((JukeboxPlayer) musicPlayer).getLocation();
            Scheduler.region(jukeboxLoc, () -> {
                // setSong rotates the jukebox record slot + adjacent chest inventory, so it must
                // run on the jukebox's region (not the viewer's) -- keep it inside this hop.
                this.spModel.getPlayList().setSong(song);
                this.spModel.createNextPlayer();
                JukeboxPlayer newPlayer = AbstractBlockPlayer.findByLocation(jukeboxLoc);
                if (newPlayer != null && this.viewer != null) {
                    Scheduler.entity(this.viewer, () -> {
                        this.stopUpdateTask();
                        this.viewer.closeInventory();
                        Scheduler.entityLater(this.viewer, () -> newPlayer.getControl().open(this.viewer), 2L);
                    });
                }
            });
        } else if (musicPlayer instanceof TextDisplayPlayer) {
            TextDisplayPlayer textPlayer = (TextDisplayPlayer) musicPlayer;
            Location loc = textPlayer.getLocation();
            // Same shape as the sign / jukebox branches: move the cursor inside the existing list,
            // then rebuild the player via createNextPlayer. Do NOT use
            // TextDisplayPlayerManager.setSong -- that replaces the whole playlist with a
            // single-song list. createNextPlayer (rather than a manual destroy + rebuild) reuses
            // the same list, copies loop/volume/speed/mute over and updates the registry, and it
            // resolves the song in the new player's constructor, so an unresolvable song throws
            // before the old player is destroyed and the existing display survives the failure.
            Scheduler.region(loc, () -> {
                // Use the live player's own list; this.spModel may be a stale snapshot.
                IPlayList list = textPlayer.getPlayList();
                if (list == null || list.getSongNum(song) < 0) {
                    // The clicked song is not in the current list (the panel showed a stale
                    // snapshot). Refresh instead of switching to a song the user did not pick.
                    if (this.viewer != null) {
                        Scheduler.entity(this.viewer, this::refresh);
                    }
                    return;
                }
                list.setSong(song);
                textPlayer.getMusicBoxModel().createNextPlayer();
                if (this.viewer != null) {
                    Scheduler.entity(this.viewer, () -> {
                        this.stopUpdateTask();
                        this.viewer.closeInventory();
                        Scheduler.entityLater(this.viewer, () -> {
                            MusicBoxSongPlayer newPlayer = AbstractBlockPlayer.findByLocation(loc);
                            if (newPlayer != null) {
                                newPlayer.getControl().open(this.viewer);
                            }
                        }, 2L);
                    });
                }
            });
        }
    }
    
    private boolean canSwitchSong() {
        return this.canSwitchSong(this.getCurrentMusicPlayer());
    }

    private boolean canSwitchSong(MusicBoxSongPlayer musicPlayer) {
        if (musicPlayer instanceof PlayerSongPlayer) {
            return true;
        }
        if (musicPlayer instanceof SignPlayer) {
            return this.viewer != null && ((SignPlayer) musicPlayer).isOwnerOrAdmin(this.viewer);
        }
        if (musicPlayer instanceof JukeboxPlayer) {
            return this.viewer != null;
        }
        if (musicPlayer instanceof TextDisplayPlayer) {
            if (this.viewer == null) {
                return false;
            }
            return this.viewer.hasPermission(Permissions.ADMIN)
                || ((TextDisplayPlayer) musicPlayer).getDisplayOptions().isAllowPublicEdit();
        }
        return false;
    }

    private boolean canControlLoopMode() {
        MusicBoxSongPlayer musicPlayer = this.getCurrentMusicPlayer();
        if (musicPlayer instanceof PlayerSongPlayer) {
            return true;
        }
        if (musicPlayer instanceof SignPlayer) {
            return this.viewer != null && ((SignPlayer) musicPlayer).isOwnerOrAdmin(this.viewer);
        }
        if (musicPlayer instanceof JukeboxPlayer) {
            return this.viewer != null;
        }
        if (musicPlayer instanceof TextDisplayPlayer) {
            return this.viewer != null;
        }
        return false;
    }

    private void updateProgressBar() {
        // Resolve the player once (for a block player getCurrentMusicPlayer() allocates a LocationKey
        // + map lookup) and derive context/canSwitch from it, instead of re-resolving 3x per fire.
        MusicBoxSongPlayer musicPlayer = this.getCurrentMusicPlayer();
        PlaybackContext context = PlaybackContext.fromPlayer(musicPlayer);
        ControlPanelProgressRenderer.render(
            this.gui,
            this.layoutParser,
            this.configManager,
            musicPlayer,
            context,
            this.canSwitchSong(musicPlayer),
            this.getEffectivePlaybackSpeed(musicPlayer),
            // Lazy: only the rare vanilla-record path (no progress seek) needs this fallback item,
            // so don't build an ItemStack + meta + lore on every ~2/s refresh of the common path.
            () -> this.getConfiguredControlButton(
                "playback-unavailable-progress",
                ItemUtils.createStack(Material.GRAY_STAINED_GLASS_PANE, Lang.CONTROL_PROGRESS_UNAVAILABLE.toString(), Lang.CONTROL_PROGRESS_UNAVAILABLE_LORE.toList())),
            this::refreshProgressAfterSeek
        );
    }

    // Seek only changes the cursor tick; the 10-tick polling task may miss the change
    // (PlaybackCursor.seek stores tick-1, so the new value can equal lastTick, and a paused
    // player never advances the cursor at all). Force a re-render right after the seek so the
    // bar jumps to the clicked position immediately.
    private void refreshProgressAfterSeek() {
        MusicBoxSongPlayer current = this.getCurrentMusicPlayer();
        this.lastTick = current != null ? current.getTick() : -1;
        this.updateProgressBar();
    }

    private void updateControlButtons() {
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("control-panel").getButtonMapping();
        int volumeSlot;
        int loopSlot;
        int speedSlot;
        int protectSlot;
        int stopDestroySlot;
        int playPauseSlot = this.layoutParser.getSlot(mapping.getPlayPause());
        if (playPauseSlot >= 0) {
            ClickAction playPauseAction = null;
            PlaybackContext context = this.getPlaybackContext();
            if (context != null && context.supportsPauseResume()) {
                playPauseAction = new ClickAction(() -> {
                    PlaybackContext refreshedContext = this.getPlaybackContext();
                    if (refreshedContext != null && refreshedContext.getPlayer() != null) {
                        if (refreshedContext.isPlaying()) {
                            refreshedContext.getPlayer().pause();
                        } else {
                            refreshedContext.getPlayer().resume();
                        }
                        this.updateControlButtons();
                    }
                });
            }
            this.gui.addItem(playPauseSlot, ControlPanelButtonFactory.createPlayPauseButton(this.configManager, context), playPauseAction);
        }
        if ((protectSlot = this.layoutParser.getSlot(mapping.getProtect())) >= 0) {
            ItemStack protectItem = ControlPanelButtonFactory.createProtectButton(this.configManager, currentPlayer, this.viewer);
            this.gui.addItem(protectSlot, protectItem, protectItem != null ? new PlayerClickAction(p -> this.handleTogglePreventDestroy(p)) : null);
        }
        if ((stopDestroySlot = this.layoutParser.getSlot(mapping.getStop())) >= 0) {
            if (currentPlayer instanceof SignPlayer) {
                ItemStack destroyItem = this.getConfiguredControlButton(
                    "destroy",
                    ItemUtils.createStack(Material.BARRIER, Lang.CONTROL_DESTROY_PLAYER.toString(), Lang.CONTROL_DESTROY_PLAYER_LORE.toList())
                );
                this.gui.addItem(stopDestroySlot, destroyItem, new PlayerClickAction(p -> this.handleDestroy(p)));
            } else if (currentPlayer instanceof TextDisplayPlayer) {
                this.gui.addItem(stopDestroySlot, GUIActions.getStopStack(), new PlayerClickAction(p -> this.handleStop(p)));
            } else if (currentPlayer instanceof JukeboxPlayer) {
                ItemStack stopItem = this.getConfiguredControlButton(
                    "stop",
                    ItemUtils.createStack(Material.BARRIER, Lang.STOP_BUTTON.toString(), Lang.STOP_BUTTON_LORE.toList())
                );
                this.gui.addItem(stopDestroySlot, stopItem, new PlayerClickAction(p -> this.handleStop(p)));
            } else {
                this.gui.addItem(stopDestroySlot, GUIActions.getStopStack(), new PlayerClickAction(p -> this.handleStop(p)));
            }
        }
        if ((loopSlot = this.layoutParser.getSlot(mapping.getLoop())) >= 0) {
            PlaybackContext context = this.getPlaybackContext();
            boolean canControlLoop = this.canControlLoopMode() && context != null && context.supportsLoopControl();
            boolean isJukebox = currentPlayer instanceof JukeboxPlayer;
            this.gui.addItem(loopSlot, ControlPanelButtonFactory.createLoopButton(this.configManager, context, isJukebox), canControlLoop ? new ClickAction(() -> {
                PlaybackContext refreshedContext = this.getPlaybackContext();
                if (refreshedContext != null && refreshedContext.getPlayer() != null) {
                    LoopMode newMode;
                    if (isJukebox) {
                        newMode = refreshedContext.getLoopMode() == LoopMode.OFF ? LoopMode.SINGLE : LoopMode.OFF;
                        refreshedContext.getPlayer().setLoopMode(newMode);
                    } else {
                        newMode = refreshedContext.getPlayer().toggleLoopMode();
                    }
                    this.syncLoopModeToWrapper(refreshedContext.getPlayer(), newMode);
                    this.updateControlButtons();
                }
            }) : null);
        }
        if ((volumeSlot = this.layoutParser.getSlot(mapping.getVolume())) >= 0) {
            this.gui.addItem(volumeSlot, ControlPanelButtonFactory.createVolumeButton(this.configManager, this.getCurrentMusicPlayer(), this.viewer), new ClickAction(() -> {
                this.adjustVolume(10);
            }, () -> {
                this.adjustVolume(-10);
            }));
        }
        if ((speedSlot = this.layoutParser.getSlot(mapping.getSpeed())) >= 0) {
            this.gui.addItem(speedSlot, ControlPanelButtonFactory.createSpeedButton(this.configManager, this.getCurrentMusicPlayer()), this.canControlSpeed() ? new ClickAction(() -> {
                this.adjustSpeed(MusicBox.getInstance().getConfigObject().getSpeed().getStep());
            }, () -> {
                this.adjustSpeed(-MusicBox.getInstance().getConfigObject().getSpeed().getStep());
            }) : null);
        }
        if (currentPlayer instanceof TextDisplayPlayer) {
            int editSlot = this.layoutParser.getSlot(mapping.getProtect());
            if (editSlot >= 0) {
                this.gui.addItem(editSlot, createTextDisplayEditButton(), new PlayerClickAction(p -> this.openTextDisplayEditor(p)));
            }
        }
    }

    private ItemStack createTextDisplayEditButton() {
        GUIConfigManager.TextPlayerEditConfig config = this.configManager.getTextPlayerEditConfig();
        GUIConfigManager.HotbarButtonConfig button = config.getButton("edit-display");
        return button != null ? button.createItem() : ItemUtils.createStack(
            Material.PAPER,
            Lang.EDIT_DISPLAY_BUTTON.toString(),
            Lang.EDIT_DISPLAY_BUTTON_LORE.toList()
        );
    }

    private void openTextDisplayEditor(Player player) {
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        if (currentPlayer instanceof TextDisplayPlayer textDisplayPlayer) {
            this.stopUpdateTask();
            new TextDisplayPlayerEditGUI(textDisplayPlayer.getName()).open(player);
        }
    }

    private void syncLoopModeToWrapper(MusicBoxSongPlayer songPlayer, LoopMode newMode) {
        PlayerWrapper wrapper;
        if (songPlayer instanceof PlayerSongPlayer && (wrapper = GUIActions.playerWrapperOf(songPlayer)) != null) {
            wrapper.setLoopMode(newMode);
        }
    }

    private void adjustVolume(int delta) {
        MusicBoxSongPlayer currentPlayer = this.getCurrentMusicPlayer();
        if (currentPlayer instanceof AbstractBlockPlayer) {
            ((AbstractBlockPlayer) currentPlayer).adjustStoredVolume(delta);
            this.refresh();
            return;
        }
        if (currentPlayer instanceof PlayerSongPlayer && currentPlayer.getMusicBoxModel().getPositionPlayer() != null) {
            PlayerWrapper wrapper = GUIActions.playerWrapperOf(currentPlayer);
            if (wrapper != null && wrapper.getPlayer() != null) {
                com.huidu.musicboxplus.core.player.VolumeManager.getInstance().setVolumeByAmount(
                    wrapper.getPlayer(),
                    com.huidu.musicboxplus.core.player.VolumeManager.getPlayerVolume(wrapper.getPlayer()) + delta
                );
                this.refresh();
                return;
            }
        }
        if (this.viewer != null) {
            com.huidu.musicboxplus.core.player.VolumeManager.getInstance().setVolumeByAmount(
                this.viewer, 
                com.huidu.musicboxplus.core.player.VolumeManager.getPlayerVolume(this.viewer) + delta
            );
            this.refresh();
        }
    }

    public void open(Player p) {
        this.viewer = p;
        this.initializeLayout();
        this.gui.open(p);
        this.refresh();
        com.huidu.musicboxplus.module.edit.gui.EditGUIListener.setControlGUI(p, this);
        // After the open lands, not before it: GUI.open defers to the player's region, and the
        // task's own guard cancels it the moment the player is not looking at this panel -- so
        // started inline, its first firing saw the previous screen and shut the task down.
        Scheduler.entityLater(p, this::startUpdateTask, 1L);
    }

    public void close() {
        this.stopUpdateTask();
        if (this.viewer != null) {
            com.huidu.musicboxplus.module.edit.gui.EditGUIListener.removeControlGUI(this.viewer);
        }
    }

    private ItemStack getConfiguredControlButton(String name, ItemStack fallback) {
        ItemStack item = this.configManager.createButtonItem("control-panel", name);
        return item != null ? item : fallback;
    }

    public GUI getGui() {
        return this.gui;
    }
}
