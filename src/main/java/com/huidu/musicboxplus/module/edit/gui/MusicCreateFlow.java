package com.huidu.musicboxplus.module.edit.gui;

import java.util.UUID;
import org.bukkit.entity.Player;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.gui.GUIInputManager;

// Ask for a name, create the music, open the editor.
//
// Reached from two places -- the edit menu's create button and the music-select screen's -- which
// held identical copies of the whole flow, limit check and async callback included. Two copies of
// a create path is how one of them ends up checking the per-player limit and the other not.
final class MusicCreateFlow {

    private MusicCreateFlow() {
    }

    static void start(Player player) {
        PlayerMusicManager musicManager = PlayerMusicManager.getInstance();

        if (musicManager.canCreateMore(player)) {
            int limit = musicManager.getMusicLimit(player);
            MessageUtils.send(player, Lang.EDIT_CREATE_LIMIT, "{limit}", String.valueOf(limit));
            return;
        }

        GUIInputManager.getInstance().requestInput(player, GUIInputManager.InputType.SEARCH_QUERY,
                Lang.EDIT_USAGE_CREATE.toComponent(), new GUIInputManager.InputCallback() {
            @Override
            public void onInputReceived(Player p, String name) {
                Scheduler.entity(player, () -> create(player, musicManager, name));
            }

            @Override
            public void onInputCancelled(Player p) {
                MessageUtils.send(p, Lang.CANCELLED);
            }
        });
    }

    private static void create(Player player, PlayerMusicManager musicManager, String name) {
        if (name == null || name.trim().isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_CREATE_FAILED_MSG);
            return;
        }

        String trimmedName = name.trim();
        PlayerMusic existing = musicManager.getMusicByName(player.getUniqueId(), trimmedName);
        if (existing != null) {
            MessageUtils.send(player, Lang.EDIT_CREATE_EXISTS, "{name}", trimmedName);
            return;
        }

        String authorName = player.getName();
        UUID authorUUID = player.getUniqueId();
        int musicLimit = musicManager.getMusicLimit(player);
        musicManager.createMusicAsync(trimmedName, authorName, authorUUID, musicLimit).thenAccept(music ->
                Scheduler.entity(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (music != null) {
                        MessageUtils.send(player, Lang.EDIT_CREATE_SUCCESS, "{name}", music.getName());
                        MusicEditListener.enterEditMode(player, music);
                    } else {
                        MessageUtils.send(player, Lang.EDIT_CREATE_FAILED_MSG);
                    }
                })
        );
    }
}
