package com.huidu.musicboxplus.module;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.player.PlayerManager;
import com.huidu.musicboxplus.core.player.models.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.core.song.PlayerSongServices;
import com.huidu.musicboxplus.module.edit.MusicEditGUI;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicDiscHelper;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.audio.ResourcePackInstrumentUtils;
import com.huidu.musicboxplus.module.edit.io.MidiAutoConverter;
import com.huidu.musicboxplus.module.edit.io.PlayerMusicCompiler;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import com.huidu.musicboxplus.module.gui.song.SongPlayerControlGUI;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.jukebox.listener.JukeboxChestListener;
import com.huidu.musicboxplus.module.jukebox.listener.JukeboxHopperListener;
import com.huidu.musicboxplus.module.listener.BlockInteractionListener;
import com.huidu.musicboxplus.module.listener.ChunkListener;
import com.huidu.musicboxplus.module.listener.RedstoneListener;
import com.huidu.musicboxplus.module.radio.RadioPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.speaker.SpeakerPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayerListener;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayerManager;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;

// Brings runtime state (event listeners, manager singletons, active players) in line with the
// currently enabled modules. Invoked once on enable and again on every reload, so every branch
// must be idempotent. Owns the per-feature listener handles it registers; MusicBox does not
// track them.
//
// Switching a module off stops it from running; it never edits the world to match. A config
// toggle is reversible by definition, so clearing signs or deleting display entities on the way
// out would turn a one-character mistake into permanent damage to someone's build.
public final class ModuleRuntimeSync {
    private final MusicBox plugin;
    private Listener jukeboxHopperListener;
    private Listener jukeboxChestListener;
    private Listener textDisplayPlayerListener;
    private Listener redstoneListener;
    private Listener blockInteractionListener;
    private Listener chunkListener;

    public ModuleRuntimeSync(MusicBox plugin) {
        this.plugin = plugin;
    }

    public void syncAll() {
        syncControlGuiFactory();
        syncPlaybackFactories();
        syncPlayerSongServices();
        syncPlaybackRuntimeState();
        syncCrossModuleListeners();
        syncJukeboxRuntimeState();
        syncTextPlayerRuntimeState();
        syncBlockPlayerRuntimeState();
        syncEditorRuntimeState();
        syncPublishedRuntimeState();
    }

    // Same pattern as the GUI factory: core.playback.PlayerWrapper creates its concrete players
    // through factories registered here, so it never imports module classes.
    private void syncPlaybackFactories() {
        PlayerWrapper.setPlayerFactories(SpeakerPlayer::new, RadioPlayer::new);
    }

    // Core cannot construct the module-layer control GUI; hand it a factory once. Idempotent:
    // re-registering the same lambda on every reload is harmless.
    private void syncControlGuiFactory() {
        MusicBoxSongPlayerModel.setControlGuiFactory(SongPlayerControlGUI::new);
    }

    // Same for the player-music services (compiling in-editor music and resolving its discs).
    // Idempotent; the lambdas re-capture the same singletons on every reload.
    private void syncPlayerSongServices() {
        PlayerSongServices.register(
            (music, overrides) -> PlayerMusicCompiler.compile((PlayerMusic) music, overrides),
            ResourcePackInstrumentUtils::buildSoundOverrides,
            meta -> PlayerMusicDiscHelper.findMusicId(meta).flatMap(id -> {
                PlayerMusicManager manager = PlayerMusicManager.getExistingInstance();
                return manager == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.ofNullable(manager.getMusicById(id));
            }),
            MidiAutoConverter::convertFolder
        );
    }

    // Registered once and kept for the plugin's lifetime. The individual handlers decide
    // for themselves whether their module is enabled, so these do not need unregistering
    // when a module is switched off -- unlike the per-feature listeners below.
    private void syncCrossModuleListeners() {
        if (redstoneListener == null) {
            redstoneListener = new RedstoneListener();
            Bukkit.getPluginManager().registerEvents(redstoneListener, plugin);
        }
        if (blockInteractionListener == null) {
            blockInteractionListener = new BlockInteractionListener();
            Bukkit.getPluginManager().registerEvents(blockInteractionListener, plugin);
        }
        if (chunkListener == null) {
            chunkListener = new ChunkListener();
            Bukkit.getPluginManager().registerEvents(chunkListener, plugin);
        }
    }

    private void syncPlaybackRuntimeState() {
        if (plugin.usesAnyPlaybackRuntime()) {
            PlayerManager.initialize();
        } else {
            PlayerManager.shutdown();
        }
        if (!plugin.isPlaybackModuleEnabled()) {
            PlayerWrapper.clearAll();
        }
    }

    private void syncJukeboxRuntimeState() {
        if (plugin.isJukeboxModuleEnabled()) {
            if (jukeboxHopperListener == null) {
                jukeboxHopperListener = new JukeboxHopperListener();
                Bukkit.getPluginManager().registerEvents(jukeboxHopperListener, plugin);
            }
            if (jukeboxChestListener == null) {
                jukeboxChestListener = new JukeboxChestListener();
                Bukkit.getPluginManager().registerEvents(jukeboxChestListener, plugin);
            }
            return;
        }
        unregisterListener(jukeboxHopperListener);
        unregisterListener(jukeboxChestListener);
        jukeboxHopperListener = null;
        jukeboxChestListener = null;
    }

    private void syncTextPlayerRuntimeState() {
        if (plugin.isTextPlayerModuleEnabled()) {
            if (textDisplayPlayerListener == null) {
                textDisplayPlayerListener = new TextDisplayPlayerListener();
                Bukkit.getPluginManager().registerEvents(textDisplayPlayerListener, plugin);
            }
            return;
        }
        unregisterListener(textDisplayPlayerListener);
        textDisplayPlayerListener = null;
        TextDisplayPlayerManager.pauseAll();
    }

    private void syncBlockPlayerRuntimeState() {
        releaseDisabledBlockPlayers();
        if (plugin.isSignsModuleEnabled() || plugin.isJukeboxModuleEnabled() || plugin.isTextPlayerModuleEnabled()) {
            AbstractBlockPlayer.registerWorldUnloadListener();
        } else {
            AbstractBlockPlayer.shutdown();
        }
    }

    // Text displays are handled by syncTextPlayerRuntimeState, which pauses them in place
    // rather than routing them through here.
    private void releaseDisabledBlockPlayers() {
        if (plugin.isSignsModuleEnabled() && plugin.isJukeboxModuleEnabled()) {
            return;
        }
        for (AbstractBlockPlayer player : new ArrayList<>(AbstractBlockPlayer.getAll())) {
            if (player instanceof SignPlayer signPlayer && !plugin.isSignsModuleEnabled()) {
                signPlayer.releaseForDisabledModule();
            } else if (player instanceof JukeboxPlayer && !plugin.isJukeboxModuleEnabled()) {
                // The jukebox keeps its disc: destroy() only tears the player down, and the disc
                // is an item in a block that the owner can still take back out by hand.
                player.destroy(DestroyReason.RELOAD);
            }
        }
    }

    private void syncEditorRuntimeState() {
        MusicEditGUI.cleanupModuleTextInputSessions();
        // Registered whatever the editor module is set to. Besides the editor's own screens,
        // this listener is the only thing that stops a song player control panel's refresh
        // task when its window closes, and that panel is reachable from a jukebox with the
        // editor switched off. Left unregistered, every panel ever opened keeps a task alive
        // that eventually closes whichever window the player has open by then.
        //
        // The editor-only branches inside it stay unreachable on their own: their screens
        // cannot be opened while the module is off.
        com.huidu.musicboxplus.module.edit.gui.EditGUIListener.register();
        if (plugin.usesPlayerMusicLibrary()) {
            MusicEditListener.register();
            if (!plugin.isEditorModuleEnabled()) {
                MusicEditListener.saveAllActive();
                MusicEditListener.restoreAllPending();
            }
            PlayerMusicManager.getInstance();
            return;
        }
        MusicEditListener.saveAllActive();
        MusicEditListener.restoreAllPending();
        MusicEditListener.unregister();
        PlayerMusicManager existing = PlayerMusicManager.getExistingInstance();
        if (existing != null) {
            existing.shutdown();
        }
    }

    private void syncPublishedRuntimeState() {
        if (plugin.usesPublishedMusicLibrary()) {
            PublishedMusicManager.getInstance();
        } else {
            PublishedMusicManager existing = PublishedMusicManager.getExistingInstance();
            if (existing != null) {
                existing.shutdown();
            }
        }
    }

    private void unregisterListener(Listener listener) {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }
}
