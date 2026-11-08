package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.module.edit.gui.ExitConfirmGUI;
import org.bukkit.entity.Player;

final class MusicEditCloseCoordinator {
    private final Player player;
    private final PlayerMusic music;
    private final MusicEditSessionGuard session;

    MusicEditCloseCoordinator(Player player, PlayerMusic music, MusicEditSessionGuard session) {
        this.player = player;
        this.music = music;
        this.session = session;
    }

    void close(boolean isPlaying, Runnable stopAutoSave, Runnable stopMusic, Runnable markSaved, Runnable finishClose) {
        if (!session.canEdit()) return;
        stopAutoSave.run();
        if (isPlaying) stopMusic.run();
        markSaved.run();
        finishClose.run();
    }

    void closeAndSave(boolean isPlaying,
                      Runnable stopAutoSave,
                      Runnable stopMusic,
                      Runnable markSaved,
                      Runnable markSaveFailed,
                      Runnable finishClose,
                      Runnable reopenAction,
                      SaveAction saveAction) {
        long token = session.beginSave();
        if (token < 0) return;
        stopAutoSave.run();
        if (isPlaying) stopMusic.run();
        SaveCallback completion = success -> session.completeSave(token, success, () -> {
            markSaved.run();
            finishClose.run();
            MessageUtils.send(player, Lang.EDIT_SAVED, "{name}", music.getName());
        }, () -> {
            markSaveFailed.run();
            MessageUtils.send(player, Lang.SAVE_FAILED_RETRY);
            reopenAction.run(); // Also restarts auto-save; keep the same lease and inventory backup.
        });
        try {
            saveAction.save(completion);
        } catch (RuntimeException exception) {
            completion.complete(false);
        }
    }

    void handleExternalCloseRequest(boolean openingSubGUI,
                                    boolean hasUnsavedChanges,
                                    boolean isPlaying,
                                    Runnable closeAction,
                                    Runnable closeAndSaveAction,
                                    Runnable reopenAction) {
        if (openingSubGUI || !session.canEdit()) return;
        if (!hasUnsavedChanges && !isPlaying) {
            closeAction.run();
            return;
        }
        long token = session.beginConfirmation();
        if (token < 0) return;
        try {
            ExitConfirmGUI confirmGUI = new ExitConfirmGUI(
                    player,
                    music.getName(),
                    session.confirmationAction(token, closeAndSaveAction),
                    session.confirmationAction(token, closeAction),
                    session.confirmationAction(token, reopenAction)
            );
            confirmGUI.open();
        } catch (RuntimeException exception) {
            session.confirmationAction(token, reopenAction).run();
            throw exception;
        }
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
