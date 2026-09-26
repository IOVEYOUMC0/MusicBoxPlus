package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.edit.gui.PastePreviewGUI;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MusicEditClipboardCoordinator {
    private final Player player;
    private final UUID playerId;
    private final PlayerMusic music;

    MusicEditClipboardCoordinator(Player player, PlayerMusic music) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.music = music;
    }

    int getClipboardSize() {
        return NoteClipboard.getInstance(playerId).getSize();
    }

    void copySelection(List<MusicNote> selectedNotes) {
        if (selectedNotes.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_SELECTION);
            return;
        }

        NoteClipboard.getInstance(playerId).copy(selectedNotes);
        MessageUtils.send(player, Lang.EDIT_NOTES_COPIED, "{count}", String.valueOf(selectedNotes.size()));
    }

    void pasteToPosition(int targetPitch,
                         int targetTick,
                         Runnable enterSubGui,
                         Runnable exitSubGui,
                         Runnable onNotesChanged,
                         BatchActionRecorder batchAddRecorder) {
        NoteClipboard clipboard = NoteClipboard.getInstance(playerId);
        if (!clipboard.hasContent()) {
            MessageUtils.send(player, Lang.EDIT_CLIPBOARD_EMPTY);
            return;
        }

        List<EditAction.NoteData> pastedData = clipboard.getPastedNotes(targetTick, targetPitch);
        List<MusicNote> addedNotes = new ArrayList<>();

        for (EditAction.NoteData data : pastedData) {
            MusicNote existingNote = music.getNote(data.getPitch(), data.getTick());
            if (existingNote == null) {
                addedNotes.add(data.createNote());
            }
        }

        if (addedNotes.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_PASTE_NO_SPACE);
            return;
        }

        Runnable reopenEditor = () -> {
            MusicEditGUI gui = MusicEditListener.getOpenGUI(playerId);
            if (gui != null && player.isOnline()) {
                Scheduler.entity(player, gui::open);
            }
        };

        enterSubGui.run();
        new PastePreviewGUI(player, addedNotes, () -> {
            exitSubGui.run();
            for (MusicNote note : addedNotes) {
                music.addNote(note);
            }
            batchAddRecorder.record(addedNotes);
            onNotesChanged.run();
            MessageUtils.send(player, Lang.EDIT_NOTES_PASTED, "{count}", String.valueOf(addedNotes.size()));
            reopenEditor.run();
        }, () -> {
            exitSubGui.run();
            MessageUtils.send(player, Lang.EDIT_PASTE_CANCELLED);
            reopenEditor.run();
        }).open();
    }

    @FunctionalInterface
    interface BatchActionRecorder {
        void record(List<MusicNote> notes);
    }
}
