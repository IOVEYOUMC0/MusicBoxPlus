package com.huidu.musicboxplus.module;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.cache.CacheUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.player.AbstractEnginePlayer;
import com.huidu.musicboxplus.core.player.PlayerManager;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.core.song.MusicBoxSongContainer;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayStore;
import com.huidu.musicboxplus.module.web.WebEditorServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

// Runs the ordered teardown chain when the plugin is disabled. One failing step must not
// abort the rest -- Paper's plugin classloader rejects never-linked classes mid-disable
// with a NoClassDefFoundError -- so every step is wrapped in its own try/catch(Throwable).
public final class ShutdownSteps {
    private final MusicBox plugin;

    public ShutdownSteps(MusicBox plugin) {
        this.plugin = plugin;
    }

    public void runAll() {
        // Snapshot prevented-sign locations BEFORE any teardown so we have something to
        // persist even if subsequent steps fail.
        final List<Location> signLocations = new ArrayList<>();
        safeDisable("snapshot prevented signs", () ->
            signLocations.addAll(SignPlayer.getPreventedPlayers().stream()
                .map(AbstractBlockPlayer::getLocation)
                .toList()));

        // Ahead of every teardown step: the displays are only in memory, and a destroyed one is
        // dropped from the registry, so saving later would write an empty file over a good one.
        safeDisable("save text displays", () -> {
            TextDisplayStore.stopAutoSave();
            TextDisplayStore.saveNow();
        });

        safeDisable("shutdown web editor", () -> {
            WebEditorServer web = plugin.getWebEditorServer();
            if (web != null) {
                web.shutdown();
            }
        });

        safeDisable("save recent songs for online players", () -> {
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                PlayerWrapper.getInstanceOptional(onlinePlayer).ifPresent(PlayerWrapper::saveRecentSongsNow);
            }
        });

        safeDisable("save active editor sessions", MusicEditListener::saveAllActive);
        safeDisable("restore pending editor inventories", MusicEditListener::restoreAllPending);
        safeDisable("unregister edit listener", MusicEditListener::unregister);
        safeDisable("close all open GUIs", GUIActions::closeAllOpen);
        safeDisable("unregister GUI listener", GUI::unregisterListener);
        // Control-panel GUIs are tracked separately from open inventories; without this a hot
        // reload (PlugMan-style, no PlayerQuitEvent) leaves the update tasks and map entries behind.
        safeDisable("unregister edit GUI listener", com.huidu.musicboxplus.module.edit.gui.EditGUIListener::unregister);
        // Before the players, and it waits for the clock thread to finish: a tick coming due
        // mid-teardown would dispatch onto a plugin that is already disabled, which Bukkit
        // rejects with a stack trace. Players left registered on a stopped clock are harmless --
        // they are torn down on the next line.
        safeDisable("shutdown playback clock", AbstractEnginePlayer::shutdownClock);
        safeDisable("shutdown block players", AbstractBlockPlayer::shutdown);
        // AbstractBlockPlayer.shutdown() only knows block players. TextDisplayPlayerManager owns
        // the song-less IdleTextDisplay handles and their floating entities, which are not block
        // players; without this they and the HANDLES/INTERACTIONS maps survive a hot reload.
        safeDisable("shutdown text display manager", com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayerManager::shutdown);
        safeDisable("shutdown async task manager", () -> AsyncTaskManager.getInstance().shutdown());
        safeDisable("shutdown player manager", PlayerManager::shutdown);

        safeDisable("shutdown player music manager", () -> {
            PlayerMusicManager playerMusicManager = PlayerMusicManager.getExistingInstance();
            if (playerMusicManager != null) {
                playerMusicManager.shutdown();
            }
        });
        safeDisable("shutdown published music manager", () -> {
            PublishedMusicManager publishedMusicManager = PublishedMusicManager.getExistingInstance();
            if (publishedMusicManager != null) {
                publishedMusicManager.shutdown();
            }
        });

        safeDisable("save prevented signs to database", () -> {
            if (!signLocations.isEmpty() && DatabaseLoader.isInitialized()) {
                try {
                    DatabaseLoader.getBase().savePreventedSigns(signLocations);
                    plugin.getLogger().info(logText("Saved " + signLocations.size() + " prevented signs", "已保存 " + signLocations.size() + " 个受保护告示牌"));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, logText("Failed to save prevented signs", "保存受保护告示牌失败"), e);
                }
            }
        });

        safeDisable("shutdown cache utils", CacheUtils::shutdown);
        safeDisable("shutdown song container loader", MusicBoxSongContainer::shutdownLoader);
        safeDisable("destroy player wrappers", plugin::destroyAllPlayers);
        safeDisable("shutdown volume manager", VolumeManager::shutdown);
        safeDisable("shutdown database", DatabaseLoader::shutdown);
    }

    private String logText(String english, String chinese) {
        return LogLocale.text(plugin, english, chinese);
    }

    private void safeDisable(String label, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Shutdown step failed (continuing): " + label, t);
        }
    }
}
