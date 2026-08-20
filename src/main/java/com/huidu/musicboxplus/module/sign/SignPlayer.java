package com.huidu.musicboxplus.module.sign;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.ConfigManager;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.*;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

// Block player driven by a sign, with redstone control on its side pins.
public class SignPlayer
extends AbstractBlockPlayer {
    private static final long SIGN_EXISTENCE_CHECK_INTERVAL_MILLIS = 5000L;
    private final Sign sign;
    private boolean preventDestroy;
    private Location infoSign;
    private SignTextDisplayPlayer infoTextDisplayPlayer;
    private volatile long nextExistenceCheckAt = 0L;
    @Nullable
    private final UUID ownerUuid;

    private SignPlayer(IPlayList list, int range, Sign sign, @Nullable UUID ownerUuid) {
        this(list, range, sign, ownerUuid, 1.0f);
    }

    private SignPlayer(IPlayList list, int range, Sign sign, @Nullable UUID ownerUuid, float speedMultiplier) {
        super(list, sign.getLocation(), range, speedMultiplier);
        this.sign = sign;
        this.ownerUuid = ownerUuid;
        String line3 = SignUtils.getSignLine(sign, 3);
        this.preventDestroy = line3 != null && line3.contains("P");
        if (this.preventDestroy) {
            this.getRangePlayerModel().setAutoDestroyMillis(0);
        }
        this.updateSignDisplayText();
        this.setupInfoSign();
    }

    private void updateSignDisplayText() {
        if (this.sign != null) {
            SignUtils.setSignLine(this.sign, 1, MiniMessageUtils.processComponent(ConfigManager.getInstance().getSignDisplayText()));
            this.sign.update(true);
        }
    }

    private SignPlayer(IPlayList list, int range, Sign sign) {
        this(list, range, sign, null);
    }

    public boolean isOwnerOrAdmin(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(Permissions.ADMIN)) {
            return true;
        }
        if (this.ownerUuid == null) {
            return true;
        }
        return this.ownerUuid.equals(player.getUniqueId());
    }
    
    public boolean canToggleProtect(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(Permissions.ADMIN)) {
            return true;
        }
        if (!player.hasPermission(Permissions.SIGN_PROTECT)) {
            return false;
        }
        if (this.ownerUuid == null) {
            return true;
        }
        return this.ownerUuid.equals(player.getUniqueId());
    }

    public static Set<SignPlayer> getPreventedPlayers() {
        Set<SignPlayer> result = new HashSet<>();
        for (AbstractBlockPlayer player : SignPlayer.getAll()) {
            if (player instanceof SignPlayer sp && sp.isPreventDestroy()) {
                result.add(sp);
            }
        }
        return result;
    }

    public static void savePreventedPlayersAsync() {
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                List<Location> signLocations = SignPlayer.getPreventedPlayers().stream()
                        .map(AbstractBlockPlayer::getLocation)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                DatabaseLoader.getBase().savePreventedSigns(signLocations);
            }
            catch (Exception e) {
                MusicBox.getInstance().getLogger().warning("保存受保护告示牌失败: " + e.getMessage());
            }
        });
    }

    public static void redstoneSign(Sign sign, int pin, int newCurrent) {
        SignPlayer player = AbstractBlockPlayer.findByLocation(sign.getLocation());
        if (player == null || newCurrent <= 0) {
            return;
        }
        
        String line3 = SignUtils.getSignLine(sign, 3);
        boolean preventDestroy = line3 != null && line3.contains("P");
        
        if (preventDestroy) {
            switch (pin) {
                case 1:
                    player.getPlayList().back(1);
                    player.getMusicBoxModel().createNextPlayer();
                    break;
                case 2:
                    player.getPlayList().next();
                    player.getMusicBoxModel().createNextPlayer();
                    break;
                default:
                    break;
            }
        } else {
            IPlayList list = player.getPlayList();
            int range = player.getRange();
            UUID ownerUuid = player.getOwnerUuid();
            switch (pin) {
                case 1:
                    list.back(1);
                    break;
                case 2:
                    list.next();
                    break;
                default:
                    return;
            }
            // The old player is torn down inside the callback, so a cold song does not leave the
            // sign silent for the length of the compile.
            com.huidu.musicboxplus.core.player.PlaybackSetup.whenReady(list,
                    run -> Scheduler.region(sign.getLocation(), run),
                    () -> {
                        player.destroyWithoutSaving();
                        new SignPlayer(list, range, sign, ownerUuid);
                    });
        }
    }

    public static void createSign(Sign sign) {
        SignPlayer.createSignWithOwner(sign, null);
    }

    public static void createSignWithOwner(Sign sign, @Nullable UUID ownerUuid) {
        SignPlayer existingPlayer = AbstractBlockPlayer.findByLocation(sign.getLocation());
        if (existingPlayer != null && !existingPlayer.isDestroyed()) {
            return;
        }
        int range = SignUtils.parseSignRange(sign);
        SignPlaylistUtils.parseSignPlaylistAsync(sign).thenAccept(optionalPlaylist -> optionalPlaylist.ifPresent(list -> {
            com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
                Integer savedSongHash = DatabaseLoader.getBase().getSignSong(sign.getLocation());
                if (savedSongHash != null) {
                    MusicBoxSongManager.findSongByHash(savedSongHash).ifPresent(list::setSong);
                }
                // Already off the server threads, so build the arrangement here rather than
                // letting the constructor do it after the hop, on the region thread.
                com.huidu.musicboxplus.core.player.PlaybackSetup.warm(list);
                // Sign creation touches the sign block -> run on the block's region.
                Scheduler.region(sign.getLocation(), () -> {
                    SignPlayer newPlayer = new SignPlayer(list, range, sign, ownerUuid);
                    if (ownerUuid != null) {
                        Player player = Bukkit.getPlayer(ownerUuid);
                        if (player != null) {
                            // Messaging + opening the control GUI belong to the player's region.
                            Scheduler.entity(player, () -> {
                                MessageUtils.send(player, Lang.SIGN_CREATED);
                                MessageUtils.send(player, Lang.SIGN_CONTROL_HINT);
                                MessageUtils.send(player, Lang.SIGN_OPTIONS_HINT);
                                Scheduler.entityLater(player, () -> newPlayer.getControl().open(player), 1L);
                            });
                        }
                    }
                });
            });
        })).exceptionally(throwable -> {
            MusicBox.getInstance().getLogger().warning("Failed to create sign playlist: " + throwable.getMessage());
            return null;
        });
    }

    public static void restorePreventedPlayers() {
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            List<Location> locations = DatabaseLoader.getBase().getPreventedSigns();
            for (Location location : locations) {
                if (location.getWorld() == null) {
                    continue;
                }
                Scheduler.region(location, () -> {
                    // Only restore signs in already-loaded chunks: touching an unloaded one would
                    // force a synchronous chunk load and stall the thread.
                    if (!location.isChunkLoaded()) {
                        return;
                    }
                    @NotNull BlockState b = location.getBlock().getState();
                    if (b instanceof Sign sign && SignPlayer.isPlayerSign(sign)) {
                        SignPlayer.createSign(sign);
                    }
                });
            }
        });
    }

    public static boolean isPlayerSign(Sign s) {
        String lineOne = SignUtils.getSignLine(s, 1);
        String strippedLine = StringUtils.stripAllColors(lineOne).trim();
        if (ConfigManager.getInstance().isValidSignAlias(strippedLine)) {
            return true;
        }
        String setupText = ConfigManager.getInstance().getSignSetupText();
        String strippedSetupText = StringUtils.stripAllColors(setupText).trim();
        if (strippedLine.equalsIgnoreCase(strippedSetupText)) {
            return true;
        }
        String displayText = ConfigManager.getInstance().getSignDisplayText();
        String strippedDisplayText = StringUtils.stripAllColors(displayText).trim();
        return strippedLine.equalsIgnoreCase(strippedDisplayText);
    }

    private void pingLever() {
        Location target = this.getTargetLocation();
        if (target == null) {
            return;
        }
        Scheduler.regionNow(target, () -> {
            @NotNull Block block = target.getBlock();
            BlockFace face = VersionUtils.getRotation(block);
            @NotNull Block leverBlock = block.getRelative(face = FaceUtils.invertFace(face), 2);
            if (Material.matchMaterial(String.valueOf(leverBlock.getType())) == Material.LEVER) {
                VersionUtils.setLever(leverBlock, true);
                Scheduler.regionLater(leverBlock.getLocation(), () -> VersionUtils.setLever(leverBlock, false), 10L);
            }
        });
    }

    private void setupInfoSign() {
        String line3 = SignUtils.getSignLine(this.sign, 3);
        if (line3 != null && line3.contains("I")) {
            SignUtils.findSign(this.sign.getLocation()).ifPresentOrElse(s -> {
                this.infoSign = s.getLocation();
                SignPlaylistUtils.setPlayListInfo(this.infoSign, super.getPlayList());
            }, this::setupTextDisplayInfo);
        }
    }

    private void setupTextDisplayInfo() {
        if (this.infoTextDisplayPlayer == null) {
            this.infoTextDisplayPlayer = new SignTextDisplayPlayer(this.sign.getLocation());
        }
        this.infoTextDisplayPlayer.spawnOrUpdate(this.getPlayList());
    }

    private void refreshInfoDisplay() {
        if (this.infoSign != null) {
            SignPlaylistUtils.setPlayListInfo(this.infoSign, this.getPlayList());
        }
        if (this.infoTextDisplayPlayer != null) {
            this.infoTextDisplayPlayer.spawnOrUpdate(this.getPlayList());
        }
    }

    private void removeInfoTextDisplay() {
        if (this.infoTextDisplayPlayer != null) {
            this.infoTextDisplayPlayer.remove();
            this.infoTextDisplayPlayer = null;
        }
    }

    private static boolean isSignMaterial(Material material) {
        return material != null && material.name().endsWith("SIGN");
    }

    @Override
    protected SignPlayer runNextSong(IPlayList list) {
        SignPlayer nextPlayer = new SignPlayer(list, this.getRange(), this.sign, this.ownerUuid, this.getMusicBoxModel().getPlaybackSpeedMultiplier());
        this.getMusicBoxModel().copySettingsTo(nextPlayer.getMusicBoxModel());
        return nextPlayer;
    }

    @Override
    protected void songEnd() {
        this.pingLever();
    }

    private void saveCurrentSongAsync() {
        MusicBoxSong currentSong = (MusicBoxSong) this.getPlayList().getCurrent();
        if (currentSong == null || this.sign == null) {
            return;
        }
        Location location = this.sign.getLocation().clone();
        int songHash = currentSong.getHash();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().saveSignSong(location, songHash);
            }
            catch (Exception e) {
                MusicBox.getInstance().getLogger().warning("\u4fdd\u5b58\u544a\u793a\u724c\u6b4c\u66f2\u5931\u8d25: " + e.getMessage());
            }
        });
    }

    private void deleteSavedSongAsync() {
        if (this.sign == null) {
            return;
        }
        Location location = this.sign.getLocation().clone();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().deleteSignSong(location);
            }
            catch (Exception e) {
                MusicBox.getInstance().getLogger().warning("\u5220\u9664\u544a\u793a\u724c\u6b4c\u66f2\u8bb0\u5f55\u5931\u8d25: " + e.getMessage());
            }
        });
    }

    // Override the reason-taking variant rather than the no-arg destroy(): in the base class the
    // no-arg version only infers a reason and delegates here, so overriding this one covers both
    // entry points. Overriding only the no-arg version would skip the cleanup below on the
    // shutdown / world-unload / chunk-unload / reload / block-break paths.
    @Override
    public void destroy(DestroyReason reason) {
        if (this.infoSign != null) {
            Scheduler.regionNow(this.infoSign, () -> {
                try {
                    SignUtils.clearInfoSign(this.infoSign);
                }
                catch (Exception e) {
                    DebugLogger.debug("Failed to clear info sign: " + e.getMessage());
                }
            });
        }
        if (this.infoTextDisplayPlayer != null) {
            Scheduler.regionNow(this.sign.getLocation(), () -> {
                try {
                    this.removeInfoTextDisplay();
                } catch (Exception e) {
                    DebugLogger.debug("Failed to clear info text display: " + e.getMessage());
                }
            });
        }
        boolean isSwitching = this.getMusicBoxModel().isNextCreated();
        if (this.preventDestroy) {
            this.saveCurrentSongAsync();
        } else if (!isSwitching) {
            this.deleteSavedSongAsync();
            this.deleteStoredVolume();
            Scheduler.regionNow(this.sign.getLocation(), () -> {
                try {
                    Block block;
                    if (this.sign != null && (block = this.sign.getLocation().getBlock()).getState() instanceof Sign) {
                        Sign currentSign = (Sign)block.getState();
                        SignUtils.setSignLine(currentSign, 0, Component.empty());
                        SignUtils.setSignLine(currentSign, 1, MiniMessageUtils.processComponent(ConfigManager.getInstance().getSignSetupText()));
                        SignUtils.setSignLine(currentSign, 3, Component.empty());
                        currentSign.update();
                    }
                }
                catch (Exception e) {
                    DebugLogger.debug("Failed to reset sign state: " + e.getMessage());
                }
            });
        }
        super.destroy(reason);
    }

    // Teardown for a sign whose module was switched off: stops the runtime and leaves the world
    // exactly as it is.
    //
    // Every other teardown path clears the attached info sign, and destroy(reason) additionally
    // rewrites the music sign itself. Both are correct when the sign is really going away, and
    // both are wrong here: turning a module off is a configuration change, not a decision to edit
    // someone's build. The music sign keeps its text, so re-enabling the module and reloading
    // finds it again and rebuilds the player; the info sign keeps its last contents until then.
    public void releaseForDisabledModule() {
        super.destroy(DestroyReason.RELOAD);
    }

    public void destroyWithoutSaving() {
        if (this.infoSign != null) {
            Scheduler.regionNow(this.infoSign, () -> {
                try {
                    SignUtils.clearInfoSign(this.infoSign);
                }
                catch (Exception e) {
                    DebugLogger.debug("Failed to clear info sign: " + e.getMessage());
                }
            });
        }
        if (this.infoTextDisplayPlayer != null) {
            Scheduler.regionNow(this.sign.getLocation(), () -> {
                try {
                    this.removeInfoTextDisplay();
                } catch (Exception e) {
                    DebugLogger.debug("Failed to clear info text display: " + e.getMessage());
                }
            });
        }
        super.destroy(DestroyReason.MANUAL_STOP);
    }

    public Sign getSign() {
        return this.sign;
    }

    public boolean isPreventDestroy() {
        return this.preventDestroy;
    }

    public void togglePreventDestroy() {
        if (this.sign == null) {
            return;
        }
        
        String line3 = SignUtils.getSignLine(this.sign, 3);
        String newLine3;
        
        if (this.preventDestroy) {
            if (line3 != null) {
                newLine3 = line3.replace("P", "").trim();
            } else {
                newLine3 = "";
            }
            this.preventDestroy = false;
            this.getRangePlayerModel().setAutoDestroyMillis(-1);
        } else {
            newLine3 = (line3 != null ? line3 : "") + "P";
            this.preventDestroy = true;
            this.getRangePlayerModel().setAutoDestroyMillis(0);
        }
        
        // The sign block mutation + world write must run on the sign block's owning region,
        // not the clicking player's region (Folia thread-ownership requirement).
        Scheduler.region(this.sign.getLocation(), () -> {
            SignUtils.setSignLine(this.sign, 3, newLine3);
            this.sign.update(true);
        });
        savePreventedPlayersAsync();
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (now < this.nextExistenceCheckAt) {
            return;
        }
        this.nextExistenceCheckAt = now + SIGN_EXISTENCE_CHECK_INTERVAL_MILLIS;
        // tick() is scheduled on this block's region (see AbstractBlockPlayer), so the
        // block/state access below is already on the owning thread and runs inline.
        this.refreshInfoDisplay();
        Location loc = this.getTargetLocation();
        if (loc == null) {
            return;
        }
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        Material type = loc.getBlock().getType();
        if (!isSignMaterial(type)) {
            this.deleteStoredVolume();
            this.destroy();
        }
    }

    @Override
    public Location getInfoSign() {
        return this.infoSign;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }
}
