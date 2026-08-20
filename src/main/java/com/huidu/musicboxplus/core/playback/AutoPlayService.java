package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

// Starts music automatically shortly after a player joins, when enabled in config. The
// configured source can be a single song, a folder, or all songs (master); folders and
// master become a looping playlist. Requires the playback module and the musicboxplus.autoplay
// permission, respects the player's persistent opt-out, and never overrides music that is
// already playing.
public final class AutoPlayService {
    private AutoPlayService() {
    }

    public static void onJoin(Player player) {
        if (player == null || !MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        MusicBoxConfig.AutoPlayConfig cfg = MusicBox.getInstance().getConfigObject().getAutoPlay();
        if (cfg == null || !cfg.isEnable()) {
            return;
        }
        int delay = Math.max(1, cfg.getDelayTicks());
        // Spread a join storm (e.g. a queue release) across up to ~2s so 100+ players
        // don't all build/shuffle the song list and spawn players on the same tick.
        int jitter = java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 41);
        // Auto-play builds a player-owned SongPlayer -> schedule on the joining player's region.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityLater(player, () -> start(player, cfg), delay + jitter);
    }

    private static void start(Player player, MusicBoxConfig.AutoPlayConfig cfg) {
        if (player == null || !player.isOnline() || !player.hasPermission(Permissions.AUTOPLAY)) {
            return;
        }
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        if (wrapper.isAutoPlayOptedOut()) {
            return;
        }
        PlayerSongPlayer active = wrapper.getActivePlayer();
        if (active != null && !active.isDestroyed()) {
            // Don't override music the player is already listening to.
            return;
        }
        // resolve() can hit the database: a "CHEST:<id>" or "LIST:<id>" source goes through
        // getContainerById -> the container factory -> a playlist query. This method runs on the
        // joining player's region thread, so that query would block it -- and the database layer
        // logs a main-thread warning for exactly this. Resolve off-thread, then come back.
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            IPlayList playList = resolve(cfg);
            if (playList == null) {
                return;
            }
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                PlayerSongPlayer current = wrapper.getActivePlayer();
                if (current != null && !current.isDestroyed()) {
                    // Re-checked on the way back: the player may have started something during
                    // the resolve, and auto-play must never override it.
                    return;
                }
                wrapper.setLoopMode(parseLoop(cfg.getLoop()));
                wrapper.play(playList);
            });
        });
    }

    private static IPlayList resolve(MusicBoxConfig.AutoPlayConfig cfg) {
        String source = cfg.getSource() == null ? "" : cfg.getSource().trim();
        boolean shuffle = cfg.isShuffle();
        if (source.isEmpty() || source.equalsIgnoreCase("master") || source.equalsIgnoreCase("all")) {
            // Build from the raw all-songs list so the configured `shuffle` flag is
            // honored (the master container always shuffles internally).
            List<MusicBoxSong> all = MusicBoxSongManager.getAllSongs();
            if (all == null || all.isEmpty()) {
                return null;
            }
            List<MusicBoxSong> songs = new java.util.ArrayList<>(all);
            if (shuffle) {
                java.util.Collections.shuffle(songs);
            }
            return new ListPlaylist(songs, true);
        }
        Optional<MusicBoxSong> song = MusicBoxSongManager.findByName(source);
        if (song.isPresent()) {
            return new SingletonPlayList(song.get());
        }
        Optional<SongContainer> container = MusicBoxSongManager.getContainerById(source);
        return container.map(c -> fromContainer(c, shuffle)).orElse(null);
    }

    private static IPlayList fromContainer(SongContainer container, boolean shuffle) {
        if (container == null) {
            return null;
        }
        List<MusicBoxSong> songs = container.getAllSongs();
        if (songs == null || songs.isEmpty()) {
            return null;
        }
        return ListPlaylist.fromContainer(container, shuffle, true);
    }

    private static LoopMode parseLoop(String loop) {
        if (loop == null) {
            return LoopMode.ALL;
        }
        try {
            return LoopMode.valueOf(loop.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LoopMode.ALL;
        }
    }
}
