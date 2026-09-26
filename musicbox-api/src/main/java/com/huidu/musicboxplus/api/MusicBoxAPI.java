package com.huidu.musicboxplus.api;

import com.huidu.musicboxplus.api.player.PositionPlayer;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Static facade for third-party plugins.
//
// Talk to MusicBox only through this class and api.event / api.player. Do not read a disc's PDC
// yourself and do not touch classes under core / module: those are implementation details, and
// disc detection has more than one criterion, so guessing yields silently wrong behavior.
public final class MusicBoxAPI {

    private static volatile MusicBoxApiService service;

    private MusicBoxAPI() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // Called once at startup by the plugin core so this facade stays free of a compile-time
    // dependency on the implementation classes.
    public static void setService(MusicBoxApiService service) {
        MusicBoxAPI.service = service;
    }

    // Whether the item is a MusicBox disc. Covers both server-library discs (song_hash) and
    // player-made or purchased discs (player_music_id); checking only one key misses the other kind.
    // true only means the item is a MusicBox disc, not that MusicBox will drive playback --
    // use isPluginDrivenJukeboxDisc for that.
    public static boolean isMusicBoxDisc(@Nullable ItemStack item) {
        MusicBoxApiService s = service;
        return s != null && s.isMusicBoxDisc(item);
    }

    // Whether, once inserted into a jukebox, this disc is played by MusicBox's own engine.
    // This is the only correct way to tell who owns playback and end-of-song notification:
    //   true  -- MusicBox creates a player and fires the pause/resume/stop/next-song/destroy events
    //            under api.event, so downstream code can drive its own presentation off them alone.
    //   false -- not driven, even for a MusicBox disc: when customRecords.enabled and
    //            customRecords.vanillaJukeboxPlayback are both on and the song is marked
    //            jukebox-playable, playback is left to vanilla. MusicBox creates no player and
    //            fires no events, so downstream code must handle it as a plain vanilla disc.
    public static boolean isPluginDrivenJukeboxDisc(@Nullable ItemStack item) {
        MusicBoxApiService s = service;
        return s != null && s.isPluginDrivenJukeboxDisc(item);
    }

    // The live MusicBox player at a block location, or null if there is none or it has already
    // been destroyed. Useful for a low-frequency self-healing poll from downstream code:
    // "I still have a display here -- is the MusicBox player still alive?"
    @Nullable
    public static PositionPlayer getPlayerAt(@NotNull Location location) {
        MusicBoxApiService s = service;
        return s == null ? null : s.getPlayerAt(location);
    }
}
