package com.huidu.musicboxplus.module.listener;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayer;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.Consumer;

// Keeps block players in sync with chunk lifecycle. Protected signs and text display
// players are player-placed fixtures, so their chunk unload is cancelled instead of
// letting the player be destroyed; jukebox discs are restored when a chunk (re)loads.
public class ChunkListener implements Listener {

    private static Consumer<ChunkUnloadEvent> chunkCanceller;

    static {
        try {
            Method method = ChunkUnloadEvent.class.getMethod("setCancelled", Boolean.TYPE);
            chunkCanceller = event -> {
                try {
                    method.invoke(event, true);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (NoSuchMethodException e) {
            chunkCanceller = event -> event.getChunk().load();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (!MusicBox.getInstance().isSignsModuleEnabled() && !MusicBox.getInstance().isJukeboxModuleEnabled() && !MusicBox.getInstance().isTextPlayerModuleEnabled()) {
            return;
        }
        @NotNull Chunk chunk = e.getChunk();
        Set<? extends AbstractBlockPlayer> playersInChunk = AbstractBlockPlayer.findByChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
        // Decide before destroying anything: one keeper cancels the unload for the whole chunk,
        // so nothing in it may be destroyed.
        for (AbstractBlockPlayer blockPlayer : playersInChunk) {
            // Protected signs and text display players are player-placed fixtures, so cancel the
            // unload and keep them in memory. Destroying a text display player loses its
            // multi-song playlist for good: nothing rebuilds it when the chunk reloads, since
            // there is no ChunkLoadEvent listener anywhere.
            if (blockPlayer instanceof TextDisplayPlayer
                    || (blockPlayer instanceof SignPlayer && ((SignPlayer) blockPlayer).isPreventDestroy())) {
                chunkCanceller.accept(e);
                return;
            }
        }
        for (AbstractBlockPlayer blockPlayer : playersInChunk) {
            blockPlayer.destroy(DestroyReason.CHUNK_UNLOAD);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        // Same gate as the other handlers: MusicBox#isStartingUp() returns true while the plugin
        // is still starting up. Jukebox discs are resolved through the song
        // index, which does not exist until the songs finish loading, so restore must wait for
        // that as well. The startup pass in MusicBox#restoreJukeboxesInLoadedChunks handles
        // chunks that were already loaded when the plugin became ready.
        if (MusicBox.getInstance().isStartingUp() || !MusicBoxSongManager.isLoaded()) {
            return;
        }
        JukeboxPlayer.restoreJukeboxesInChunk(e.getChunk());
    }
}
