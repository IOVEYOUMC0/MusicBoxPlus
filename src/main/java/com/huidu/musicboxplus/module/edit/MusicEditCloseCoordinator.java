package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.module.edit.gui.ExitConfirmGUI;
import org.bukkit.entity.Player;

final class MusicEditCloseCoordinator {
    private final Player player;
    private final PlayerMusic music;

    MusicEditCloseCoordinator(Player player, PlayerMusic music) {
        this.player = player;
        this.music = music;
    }

    void close(boolean isPlaying, Runnable stopAutoSave, Runnable stopMusic, Runnable markSaved, Runnable finishClose) {
        stopAutoSave.run();
        if (isPlaying) {
            stopMusic.run();
        }
        markSaved.run();
        finishClose.run();
    }

    void closeAndSave(boolean isPlaying,
                      Runnable stopAutoSave,
                      Runnable stopMusic,
                      Runnable markSaved,
                      Runnable markSaveFailed,
                      Runnable finishClose,
                      SaveAction saveAction) {
        stopAutoSave.run();
        if (isPlaying) {
            stopMusic.run();
        }
        saveAction.save(success -> {
            if (success) {
                markSaved.run();
                finishClose.run();
                MessageUtils.send(player, Lang.EDIT_SAVED, "{name}", music.getName());
            } else {
                markSaveFailed.run();
                MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            }
        });
    }

    void handleExternalCloseRequest(boolean openingSubGUI,
                                    boolean hasUnsavedChanges,
                                    boolean isPlaying,
                                    Runnable closeAction,
                                    Runnable closeAndSaveAction,
                                    Runnable reopenAction) {
        if (openingSubGUI) {
            return;
        }

        if (!hasUnsavedChanges && !isPlaying) {
            closeAction.run();
            return;
        }

        ExitConfirmGUI confirmGUI = new ExitConfirmGUI(
                player,
                music.getName(),
                closeAndSaveAction,
                closeAction,
                reopenAction
        );
        confirmGUI.open();
    }

    @FunctionalInterface
    interface SaveAction {
        void save(SaveCallback callback);
    }

    @FunctionalInterface
    interface SaveCallback {
        void complete(boolean success);
    }
}
