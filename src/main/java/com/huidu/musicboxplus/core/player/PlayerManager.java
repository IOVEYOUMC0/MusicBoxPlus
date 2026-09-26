package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.common.utils.LogLocale;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

// Registry of every active music player.
//
// Folia note: there is intentionally no global tick loop here. A single server-wide timer cannot
// iterate players living in different regions - each player's MusicBoxSongPlayer.tick() touches
// its own block/entity region. Instead every player schedules its own tick on the correct
// region/entity scheduler (see AbstractBlockPlayer and SpeakerPlayer). This registry only tracks
// the live players so that shutdown/reload can destroy them all.
public final class PlayerManager {
    private static final Set<MusicBoxSongPlayer> activePlayers = ConcurrentHashMap.newKeySet();
    private static volatile boolean initialized = false;
    private static final int SNAPSHOT_THRESHOLD = 100;

    private PlayerManager() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(),
                "PlayerManager initialized (per-player region scheduling)",
                "PlayerManager 已初始化（按区域调度每个播放器）"));
    }

    public static synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        initialized = false;

        for (MusicBoxSongPlayer player : activePlayers) {
            if (!player.isDestroyed()) {
                try {
                    player.destroy();
                } catch (Throwable e) {
                    MusicBox.getInstance().getLogger().log(Level.WARNING, "销毁播放器时出错", e);
                }
            }
        }
        activePlayers.clear();

        MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(), "PlayerManager shutdown complete", "PlayerManager 已关闭"));
    }

    public static void registerPlayer(MusicBoxSongPlayer player) {
        if (player != null && !player.isDestroyed()) {
            activePlayers.add(player);
        }
    }

    public static void unregisterPlayer(MusicBoxSongPlayer player) {
        activePlayers.remove(player);
    }

    public static Collection<MusicBoxSongPlayer> getActivePlayers() {
        int size = activePlayers.size();
        if (size == 0) {
            return Collections.emptyList();
        }
        if (size > SNAPSHOT_THRESHOLD) {
            return Collections.unmodifiableCollection(activePlayers);
        }
        return new ArrayList<>(activePlayers);
    }

    public static int getActivePlayerCount() {
        return activePlayers.size();
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
