package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TextDisplayPlayerManager {
    private static final Map<String, TextDisplayHandle> HANDLES = new ConcurrentHashMap<>();
    private static final Map<UUID, String> INTERACTIONS = new ConcurrentHashMap<>();

    public static final int MIN_RANGE = 1;
    public static final int MAX_RANGE = 64;
    public static final int RANGE_STEP = 4;

    private TextDisplayPlayerManager() {
    }

    public static Optional<TextDisplayHandle> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(liveHandle(normalize(name)));
    }

    // The active (song-playing) text player for this name, if any; idle placeholders are excluded.
    public static Optional<TextDisplayPlayer> getActive(String name) {
        TextDisplayHandle handle = name == null ? null : liveHandle(normalize(name));
        return handle instanceof TextDisplayPlayer player ? Optional.of(player) : Optional.empty();
    }

    public static List<String> getNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, TextDisplayHandle> entry : HANDLES.entrySet()) {
            if (liveHandle(entry.getKey()) != null) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // Returns the handle only if it is still alive, evicting destroyed ones on the way.
    // The destroy paths (chunk unload, module shutdown, command) do not unregister, and a
    // stale handle would still show up in tab completion, open in the edit menu, and let
    // setPlaylist rebuild a player from its location/range - resurrecting the display with
    // a one-song playlist.
    private static TextDisplayHandle liveHandle(String normalizedName) {
        TextDisplayHandle handle = HANDLES.get(normalizedName);
        if (handle instanceof TextDisplayPlayer player && player.isDestroyed()) {
            HANDLES.remove(normalizedName, handle);
            return null;
        }
        return handle;
    }

    public static TextDisplayPlayer create(String name, MusicBoxSong song, Location location, int range) {
        if (!MusicBox.getInstance().isTextPlayerModuleEnabled()) {
            return null;
        }
        String normalized = normalize(name);
        TextDisplayHandle existing = HANDLES.remove(normalized);
        if (existing != null) {
            existing.destroy();
        }
        TextDisplayPlayer player = new TextDisplayPlayer(name, new SingletonPlayList(song), location, range);
        // A placed text display is meant to persist; default to looping the single song
        // smoothly instead of self-destructing (display vanishing) when it ends.
        player.getMusicBoxModel().setLoopMode(LoopMode.SINGLE);
        HANDLES.put(normalized, player);
        TextDisplayStore.saveSoon();
        return player;
    }

    // Creates a song-less placeholder text display. It shows the idle text and runs no
    // playback; assign a song later via setSong/setPlaylist, which upgrades it to a real
    // TextDisplayPlayer.
    public static IdleTextDisplay createIdle(String name, Location location, int range) {
        if (!MusicBox.getInstance().isTextPlayerModuleEnabled()) {
            return null;
        }
        String normalized = normalize(name);
        TextDisplayHandle existing = HANDLES.remove(normalized);
        if (existing != null) {
            existing.destroy();
        }
        IdleTextDisplay idle = new IdleTextDisplay(name, location, range, TextDisplayPlayer.DisplayOptions.defaults());
        HANDLES.put(normalized, idle);
        TextDisplayStore.saveSoon();
        return idle;
    }

    // Recreates a stored display without going through the module gate or the "replace what is
    // already there" logic of create/createIdle: at restore time nothing is there yet, and the
    // caller has already decided the module is on.
    static TextDisplayPlayer restoreActive(String name, IPlayList list, Location location, int range,
                                           float speedMultiplier,
                                           TextDisplayPlayer.DisplayOptions displayOptions,
                                           LoopMode loopMode) {
        TextDisplayPlayer player =
                new TextDisplayPlayer(name, list, location, range, speedMultiplier, displayOptions);
        player.getMusicBoxModel().setLoopMode(loopMode);
        HANDLES.put(normalize(name), player);
        return player;
    }

    static IdleTextDisplay restoreIdle(String name, Location location, int range,
                                       TextDisplayPlayer.DisplayOptions displayOptions) {
        IdleTextDisplay idle = new IdleTextDisplay(name, location, range, displayOptions);
        HANDLES.put(normalize(name), idle);
        return idle;
    }

    public static boolean delete(String name) {
        TextDisplayHandle handle = HANDLES.remove(normalize(name));
        if (handle == null) {
            return false;
        }
        handle.destroy();
        // The only thing that drops the stored record. Leaving it to the periodic save would mean
        // a display deleted shortly before a crash comes back on the next start, and a display
        // that merely left the registry (world unloaded, module off) would be lost.
        TextDisplayStore.forget(name);
        return true;
    }

    // Stops playback on every text display but keeps the displays, their entities and their
    // handles. Used when the module is switched off: text displays live only in memory, so
    // removing their entities here would destroy them for good -- nothing on disk can bring
    // them back, and once the handle is gone the owner cannot even delete the leftover entity.
    // Paused this way, flipping the module back on leaves everything addressable again.
    public static void pauseAll() {
        for (TextDisplayHandle handle : HANDLES.values()) {
            if (handle instanceof TextDisplayPlayer player && !player.isDestroyed()) {
                player.setPlaying(false);
            }
        }
    }

    public static void shutdown() {
        for (TextDisplayHandle handle : HANDLES.values()) {
            handle.destroy();
        }
        HANDLES.clear();
        INTERACTIONS.clear();
    }

    // Replaces the text player's whole content with a single song.
    //
    // This intentionally discards any existing multi-song playlist - that is the meaning of
    // the method. To switch to another song *within* the current playlist instead, call
    // list.setSong(song) followed by getMusicBoxModel().createNextPlayer(); see the text
    // player branch of SongPlayerControlGUI.switchToSong. Using this method there would
    // collapse the player's queue down to one song.
    public static boolean setSong(String name, MusicBoxSong song) {
        return setPlaylist(name, new SingletonPlayList(song));
    }

    // Assigns (or replaces) the text player's playlist by recreating it in place as an
    // active TextDisplayPlayer, preserving location, range, display options and - when
    // upgrading from another active player - speed and loop/volume settings. Works on both
    // an active player and a song-less IdleTextDisplay placeholder.
    public static boolean setPlaylist(String name, IPlayList list) {
        if (list == null || list.getCurrent() == null) {
            return false;
        }
        String normalized = normalize(name);
        TextDisplayHandle handle = liveHandle(normalized);
        if (handle == null) {
            return false;
        }
        Location location = handle.getLocation();
        int range = handle.getRange();
        TextDisplayPlayer.DisplayOptions displayOptions = handle.getDisplayOptions().copy();
        float speedMultiplier = handle instanceof TextDisplayPlayer previous
                ? previous.getMusicBoxModel().getPlaybackSpeedMultiplier()
                : 1.0f;
        handle.destroy();
        TextDisplayPlayer updated = new TextDisplayPlayer(handle.getName(), list, location, range, speedMultiplier, displayOptions);
        if (handle instanceof TextDisplayPlayer previous) {
            previous.getMusicBoxModel().copySettingsTo(updated.getMusicBoxModel());
        }
        // Keep the display alive and loop sensibly for the new content: cycle a
        // multi-song list (ALL), or smoothly repeat a single song (SINGLE).
        updated.getMusicBoxModel().setLoopMode(list.hasNext() ? LoopMode.ALL : LoopMode.SINGLE);
        HANDLES.put(normalized, updated);
        TextDisplayStore.saveSoon();
        return true;
    }

    // Moves the text display to newLocation. An idle placeholder is teleported in place; an
    // active player is recreated there (the block location is the registry key), preserving
    // the song, range, speed, display options, loop/volume and tick/paused state.
    public static boolean move(String name, Location newLocation) {
        if (newLocation == null) {
            return false;
        }
        String normalized = normalize(name);
        TextDisplayHandle handle = liveHandle(normalized);
        if (handle == null) {
            return false;
        }
        if (handle instanceof IdleTextDisplay idle) {
            idle.relocate(newLocation);
            return true;
        }
        TextDisplayPlayer player = (TextDisplayPlayer) handle;
        IPlayList list = player.getPlayList();
        int range = player.getRange();
        float speedMultiplier = player.getMusicBoxModel().getPlaybackSpeedMultiplier();
        TextDisplayPlayer.DisplayOptions displayOptions = player.getDisplayOptions().copy();
        short tick = player.getTick();
        boolean playing = player.isPlaying();
        player.destroy();
        TextDisplayPlayer updated = new TextDisplayPlayer(player.getName(), list, newLocation, range, speedMultiplier, displayOptions);
        player.getMusicBoxModel().copySettingsTo(updated.getMusicBoxModel());
        if (tick > 0) {
            updated.setTick(tick);
        }
        if (!playing) {
            updated.pause();
        }
        HANDLES.put(normalized, updated);
        TextDisplayStore.saveSoon();
        return true;
    }

    // Sets the audio playback range (clamped to [MIN_RANGE, MAX_RANGE]). Live for an active player.
    public static boolean setRange(String name, int range) {
        TextDisplayHandle handle = HANDLES.get(normalize(name));
        if (handle == null) {
            return false;
        }
        handle.setRange(Math.max(MIN_RANGE, Math.min(MAX_RANGE, range)));
        return true;
    }

    public static Optional<TextDisplayHandle> getByInteraction(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        String name = INTERACTIONS.get(entity.getUniqueId());
        return name != null ? get(name) : Optional.empty();
    }

    // Re-points the name -> handle mapping at player. Called when a text display player
    // chains to a new instance on song advance/loop, so the manager keeps tracking the live
    // player; otherwise delete/control/setSong would act on a stale, already-replaced
    // instance and leave the live display standing in the world.
    static void reregister(String name, TextDisplayPlayer player) {
        if (name != null && player != null) {
            HANDLES.put(normalize(name), player);
        }
    }

    static void registerInteraction(Entity entity, String name) {
        if (entity != null && name != null) {
            INTERACTIONS.put(entity.getUniqueId(), normalize(name));
        }
    }

    static void unregisterInteraction(Entity entity) {
        if (entity != null) {
            INTERACTIONS.remove(entity.getUniqueId());
        }
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase();
    }
}
