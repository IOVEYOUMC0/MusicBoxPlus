package com.huidu.musicboxplus.module.lifecycle;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Captures the live playback state (per-player radio/speaker players and block players such
// as signs and jukeboxes) before a plugin reload, tears the active players down, and
// restores them afterwards.
//
// Lives under module because it orchestrates module-owned player types (SignPlayer,
// JukeboxPlayer, TextDisplayPlayer); core must not know about them.
public final class ReloadPlaybackState {
    private final MusicBox plugin;

    public ReloadPlaybackState(MusicBox plugin) {
        this.plugin = plugin;
    }

    // Snapshot of everything that needs to survive a reload. Opaque to callers.
    public Snapshot capture() {
        List<PlayerPlaybackSnapshot> playerSnapshots = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<PlayerWrapper> wrapperOptional = PlayerWrapper.getInstanceOptional(player);
            if (wrapperOptional.isEmpty()) {
                continue;
            }
            PlayerWrapper wrapper = wrapperOptional.get();
            PlayerSongPlayer activePlayer = wrapper.getActivePlayer();
            if (activePlayer == null || activePlayer.isDestroyed() || activePlayer.getMusicBoxSong() == null) {
                continue;
            }
            PlayerPlaylistSnapshot playlistSnapshot = snapshotPlayerPlaylist(activePlayer.getPlayList());
            if (playlistSnapshot == null) {
                continue;
            }
            playerSnapshots.add(new PlayerPlaybackSnapshot(
                player.getUniqueId(),
                playlistSnapshot,
                activePlayer.getTick(),
                activePlayer.isPaused(),
                wrapper.isSpeaker(),
                wrapper.isSilent(),
                wrapper.getLoopMode(),
                wrapper.getPlaybackSpeedMultiplier()
            ));
        }

        List<BlockPlaybackSnapshot> blockSnapshots = new ArrayList<>();
        for (AbstractBlockPlayer player : AbstractBlockPlayer.getAll()) {
            if (player == null || player.isDestroyed() || player.getLocation() == null) {
                continue;
            }
            if (player instanceof SignPlayer signPlayer) {
                blockSnapshots.add(new BlockPlaybackSnapshot(BlockPlaybackType.SIGN, player.getLocation(),
                        getCurrentSongHash(player), player.getTick(), signPlayer.getOwnerUuid()));
            } else if (player instanceof JukeboxPlayer) {
                blockSnapshots.add(new BlockPlaybackSnapshot(BlockPlaybackType.JUKEBOX, player.getLocation(),
                        getCurrentSongHash(player), player.getTick(), null));
            }
        }
        return new Snapshot(playerSnapshots, blockSnapshots);
    }

    // Destroys all active block players and clears player wrappers ahead of a reload.
    public void stopPlayers() {
        for (AbstractBlockPlayer player : new ArrayList<>(AbstractBlockPlayer.getAll())) {
            // Text players are tracked by name in TextDisplayPlayerManager, so they need no
            // block re-read to be rebuilt, and BlockPlaybackSnapshot cannot hold their
            // multi-song playlist or display options. Leave them running; ModuleRuntimeSync
            // cleans them up if the module gets disabled.
            if (player instanceof TextDisplayPlayer) {
                continue;
            }
            if (player instanceof SignPlayer) {
                ((SignPlayer) player).destroyWithoutSaving();
            } else {
                player.destroy(DestroyReason.RELOAD);
            }
        }
        PlayerWrapper.clearAll();
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        for (BlockPlaybackSnapshot blockSnapshot : snapshot.blockSnapshots()) {
            if ((blockSnapshot.type() == BlockPlaybackType.SIGN && !plugin.isSignsModuleEnabled())
                    || (blockSnapshot.type() == BlockPlaybackType.JUKEBOX && !plugin.isJukeboxModuleEnabled())) {
                continue;
            }
            Location location = blockSnapshot.location();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            // Each block may live in a different region and this runs on the global
            // region thread, so hop onto the block's own region before reading its
            // state and (re)creating the sign/jukebox player.
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(location, () -> {
                location.getChunk().load();
                BlockState state = location.getBlock().getState();
                switch (blockSnapshot.type()) {
                    case SIGN:
                        if (state instanceof Sign sign && SignPlayer.isPlayerSign(sign)) {
                            SignPlayer.createSignWithOwner(sign, blockSnapshot.ownerUuid());
                            restoreBlockPlayerStateLater(location, blockSnapshot);
                        }
                        break;
                    case JUKEBOX:
                        if (state instanceof Jukebox jukebox) {
                            JukeboxPlayer.createNew(jukebox);
                            restoreBlockPlayerStateLater(location, blockSnapshot);
                        }
                        break;
                    default:
                        break;
                }
            });
        }

        if (!plugin.isPlaybackModuleEnabled()) {
            return;
        }
        for (PlayerPlaybackSnapshot playerSnapshot : snapshot.playerSnapshots()) {
            Player player = Bukkit.getPlayer(playerSnapshot.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
            wrapper.setSilent(playerSnapshot.silent());
            wrapper.setLoopMode(playerSnapshot.loopMode());
            if (wrapper.isSpeaker() != playerSnapshot.speaker()) {
                wrapper.switchMode();
            }
            wrapper.setPlaybackSpeedMultiplier(playerSnapshot.speed());

            restorePlayerPlaylist(playerSnapshot.playlist()).ifPresent(playlist -> {
                wrapper.play(playlist, playerSnapshot.tick());
                if (playerSnapshot.paused() && wrapper.getActivePlayer() != null) {
                    wrapper.getActivePlayer().pause();
                }
            });
        }
    }

    private PlayerPlaylistSnapshot snapshotPlayerPlaylist(IPlayList playList) {
        if (playList == null || playList.getCurrent() == null) {
            return null;
        }
        if (playList instanceof SingletonPlayList singletonPlayList) {
            return new PlayerPlaylistSnapshot(
                PlayerPlaylistType.SINGLETON,
                List.of(singletonPlayList.getSong().getHash()),
                0,
                false
            );
        }
        if (playList instanceof ListPlaylist listPlaylist) {
            List<Integer> hashes = listPlaylist.getSongsSnapshot().stream().map(MusicBoxSong::getHash).toList();
            return new PlayerPlaylistSnapshot(
                PlayerPlaylistType.LIST,
                hashes,
                listPlaylist.getCurrentIndex(),
                listPlaylist.hasEnd()
            );
        }
        return new PlayerPlaylistSnapshot(
            PlayerPlaylistType.SINGLETON,
            List.of(playList.getCurrent().getHash()),
            0,
            false
        );
    }

    private Optional<IPlayList> restorePlayerPlaylist(PlayerPlaylistSnapshot snapshot) {
        if (snapshot == null || snapshot.songHashes() == null || snapshot.songHashes().isEmpty()) {
            return Optional.empty();
        }
        List<MusicBoxSong> songs = snapshot.songHashes().stream()
            .map(MusicBoxSongManager::findSongByHash)
            .flatMap(Optional::stream)
            .toList();
        if (songs.isEmpty()) {
            return Optional.empty();
        }
        if (snapshot.type() == PlayerPlaylistType.LIST && songs.size() > 1) {
            ListPlaylist playlist = new ListPlaylist(songs, snapshot.hasEnd());
            int index = Math.max(0, Math.min(snapshot.currentIndex(), songs.size() - 1));
            playlist.setSong(songs.get(index));
            return Optional.of(playlist);
        }
        return Optional.of(new SingletonPlayList(songs.get(0)));
    }

    private int getCurrentSongHash(AbstractBlockPlayer player) {
        return player.getPlayList() != null && player.getPlayList().getCurrent() != null
            ? player.getPlayList().getCurrent().getHash()
            : -1;
    }

    private void restoreBlockPlayerStateLater(Location location, BlockPlaybackSnapshot snapshot) {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionLater(location, () -> {
            AbstractBlockPlayer restored = AbstractBlockPlayer.findByLocation(location);
            if (restored == null || restored.isDestroyed()) {
                return;
            }
            if (snapshot.songHash() != -1) {
                MusicBoxSongManager.findSongByHash(snapshot.songHash()).ifPresent(song -> restored.getPlayList().setSong(song));
            }
            if (snapshot.tick() > -1) {
                restored.setTick(snapshot.tick());
            }
        }, 2L);
    }

    public record Snapshot(List<PlayerPlaybackSnapshot> playerSnapshots,
                           List<BlockPlaybackSnapshot> blockSnapshots) {
    }

    private record PlayerPlaybackSnapshot(UUID playerId, PlayerPlaylistSnapshot playlist, short tick, boolean paused,
                                          boolean speaker, boolean silent, LoopMode loopMode, float speed) {
    }

    private record PlayerPlaylistSnapshot(PlayerPlaylistType type, List<Integer> songHashes, int currentIndex, boolean hasEnd) {
    }

    // ownerUuid rides along because SignPlayer.isOwnerOrAdmin treats a null owner as "everyone
    // owns this". Recreating a captured sign through createSign (owner null) therefore handed the
    // destroy button, the protect toggle and song switching to every player on the server.
    private record BlockPlaybackSnapshot(BlockPlaybackType type, Location location, int songHash, short tick,
                                         java.util.UUID ownerUuid) {
    }

    private enum BlockPlaybackType {
        SIGN,
        JUKEBOX
    }

    private enum PlayerPlaylistType {
        SINGLETON,
        LIST
    }
}
