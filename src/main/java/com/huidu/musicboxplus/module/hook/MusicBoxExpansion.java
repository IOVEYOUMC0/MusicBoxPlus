package com.huidu.musicboxplus.module.hook;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

// PlaceholderAPI's expansion contract still relies on JavaPlugin.getDescription(), which
// Bukkit deprecated with no Paper 1.21.4 replacement, so the class keeps a class-level
// suppression.
@SuppressWarnings("deprecation")
public class MusicBoxExpansion
extends PlaceholderExpansion {
    private final MusicBox plugin;

    public MusicBoxExpansion(MusicBox plugin) {
        this.plugin = plugin;
    }

    @NotNull
    public String getIdentifier() {
        return "musicboxplus";
    }

    @NotNull
    public String getAuthor() {
        return this.plugin.getDescription().getAuthors().toString();
    }

    @NotNull
    public String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null || !offlinePlayer.isOnline()) {
            return "";
        }
        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            return "";
        }
        // getInstance CREATES the wrapper on a miss, and that constructor builds a boss bar and
        // writes persistent data -- Bukkit calls that must not run on whatever thread happened to
        // ask for a placeholder. Read-only here: no wrapper yet simply means nothing is playing.
        PlayerWrapper wrapper = PlayerWrapper.getInstanceOptional(player).orElse(null);
        if (wrapper == null) {
            return "";
        }
        switch (params.toLowerCase()) {
            case "now_playing": 
            case "song": 
            case "current_song": {
                return this.getCurrentSongName(wrapper);
            }
            case "song_author": 
            case "author": {
                return this.getCurrentSongAuthor(wrapper);
            }
            case "song_original_author": 
            case "original_author": {
                return this.getCurrentSongOriginalAuthor(wrapper);
            }
            case "song_length": 
            case "length": 
            case "duration": {
                return this.getSongLength(wrapper);
            }
            case "song_progress": 
            case "progress": {
                return this.getSongProgress(wrapper);
            }
            case "song_progress_percent": 
            case "progress_percent": {
                return this.getSongProgressPercent(wrapper);
            }
            case "is_playing": 
            case "playing": {
                return this.isPlaying(wrapper);
            }
            case "is_paused": 
            case "paused": {
                return this.isPaused(wrapper);
            }
            case "status": {
                return this.getStatus(wrapper);
            }
            case "mode": 
            case "playback_mode": {
                return this.getPlaybackMode(wrapper);
            }
            case "is_speaker": 
            case "speaker_mode": {
                return wrapper.isSpeaker() ? "true" : "false";
            }
            case "is_silent": 
            case "silent_mode": {
                return wrapper.isSilent() ? "true" : "false";
            }
            case "loop_mode": {
                return this.getLoopMode(wrapper);
            }
            case "volume": {
                return this.getVolume(player);
            }
            case "total_songs": {
                return String.valueOf(MusicBoxSongManager.getAllSongs().size());
            }
            case "playlist_count": 
            case "recent_songs_count": 
            case "recent_count": {
                return this.getPlaylistCount(wrapper);
            }
            case "recent_song": 
            case "last_song": {
                return this.getRecentSong(wrapper);
            }
        }
        return null;
    }

    private String getCurrentSongName(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null || player.getMusicBoxSong() == null) {
            return Lang.PLACEHOLDER_NO_SONG.toString();
        }
        return player.getMusicBoxSong().getName();
    }

    private String getCurrentSongAuthor(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null || player.getMusicBoxSong() == null) {
            return "";
        }
        try {
            return player.getMusicBoxSong().getHoverMap().getOrDefault("{author}", "");
        }
        catch (Exception e) {
            return "";
        }
    }

    private String getCurrentSongOriginalAuthor(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null || player.getMusicBoxSong() == null) {
            return "";
        }
        try {
            return player.getMusicBoxSong().getHoverMap().getOrDefault("{original_author}", "");
        }
        catch (Exception e) {
            return "";
        }
    }

    private String getSongLength(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null || player.getMusicBoxSong() == null) {
            return "0:00";
        }
        // Already formatted against the song's own tempo by MusicBoxSong.
        return player.getMusicBoxSong().getLengthFormatted();
    }

    private String getSongProgress(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null) {
            return "0:00";
        }
        return this.formatTime(player.getTick(), ticksPerSecond(player.getMusicBoxSong()));
    }

    private String getSongProgressPercent(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null || player.getMusicBoxSong() == null) {
            return "0";
        }
        short length = player.getMusicBoxSong().getLength();
        if (length <= 0) {
            return "0";
        }
        int percent = (int)((double)player.getTick() * 100.0 / (double)length);
        return String.valueOf(Math.min(100, Math.max(0, percent)));
    }

    private String isPlaying(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        return player != null && !player.isPaused() ? "true" : "false";
    }

    private String isPaused(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        return player != null && player.isPaused() ? "true" : "false";
    }

    private String getStatus(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null) {
            return Lang.PLACEHOLDER_STATUS_STOPPED.toString();
        }
        return player.isPaused() ? Lang.PLACEHOLDER_STATUS_PAUSED.toString() : Lang.PLACEHOLDER_STATUS_PLAYING.toString();
    }

    private String getPlaybackMode(PlayerWrapper wrapper) {
        if (wrapper.isSpeaker()) {
            return Lang.PLACEHOLDER_MODE_SPEAKER.toString();
        }
        return Lang.PLACEHOLDER_MODE_RADIO.toString();
    }

    private String getLoopMode(PlayerWrapper wrapper) {
        PlayerSongPlayer player = wrapper.getActivePlayer();
        if (player == null) {
            return Lang.LOOP_MODE_OFF.toString();
        }
        return Lang.valueOf("LOOP_MODE_" + player.getLoopMode().name()).toString();
    }

    private String getVolume(Player player) {
        if (player == null) {
            return "100";
        }
        return String.valueOf(VolumeManager.getPlayerVolume(player));
    }

    private String getPlaylistCount(PlayerWrapper wrapper) {
        try {
            return String.valueOf(wrapper.getRecentSongs().size());
        }
        catch (Exception e) {
            return "0";
        }
    }

    private String getRecentSong(PlayerWrapper wrapper) {
        MusicBoxSong recent = wrapper.getRecentSong();
        return recent != null ? recent.getName() : Lang.PLACEHOLDER_NO_SONG.toString();
    }

    // A song's tempo is its own; 10 t/s is the NBS default and 20 is not a safe stand-in.
    private static float ticksPerSecond(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        return song instanceof com.huidu.musicboxplus.core.song.MusicBoxSong core ? core.getSpeed() : 0F;
    }

    // ticksPerSecond, not 20: these are SONG ticks, and dividing by 20 reported every progress
    // reading at the wrong scale.
    private String formatTime(int ticks, float ticksPerSecond) {
        int totalSeconds = ticksPerSecond <= 0 ? 0 : (int) (ticks / ticksPerSecond);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
