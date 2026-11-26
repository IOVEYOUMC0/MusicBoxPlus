package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * What a click inside the editor's note grid does: toggle a note, delete it, start a selection, or
 * open the instrument picker for an existing note.
 *
 * Moved out of MusicEditGUI so the decision and its side effects are named in one place.
 * The arithmetic that turns a layout slot into a cell lives in MusicEditGrid and is covered
 * by its own tests; this class applies the resulting cell to the song.
 *
 * Everything the handler needs from the window is behind Host, so nothing here reaches
 * into the inventory or the session directly.
 */
final class MusicEditNoteEditor {

    /** The window state one click can read and change. */
    interface Host {
        Player player();

        PlayerMusic music();

        int pitchOffset();

        int tickOffset();

        int columns();

        int maxPitch();

        /** Highest pitch in the vanilla octave range; notes above it are the extended octaves. */
        int defaultMaxPitch();

        MusicNote.NoteInstrument currentInstrument();

        void selectNote(MusicNote note);

        void enterInstrumentSelectMode();

        boolean selectionActive();

        void dropSelection();

        void beginOrExpandSelection(int slot);

        void pushEditAction(EditAction action);

        void markEdited();

        /** Refresh exactly these slots, instead of rebuilding the whole grid. */
        void refreshSlots(Set<Integer> slots);

        void refreshAll();

        void refreshHotbar();

        void previewNote(int pitch, MusicNote.NoteInstrument instrument);
    }

    private final Host host;

    MusicEditNoteEditor(Host host) {
        this.host = host;
    }

    /**
     * Applies one click. layoutIndex is the position within the edit area; the caller has already
     * checked that the clicked slot belongs to it.
     */
    void click(int slot, int layoutIndex, boolean rightClick, boolean shiftClick) {
        MusicEditGrid.Cell cell = MusicEditGrid.resolve(
                layoutIndex, host.columns(), host.pitchOffset(), host.tickOffset(), host.maxPitch());
        if (cell == null) {
            return;
        }
        int pitch = cell.pitch();
        int tick = cell.tick();

        PlayerMusic music = host.music();
        MusicNote note = music.getNote(pitch, tick);
        boolean isExtendedOctave = pitch > host.defaultMaxPitch();
        boolean canEditExtendedOctave = host.maxPitch() > host.defaultMaxPitch();

        if (shiftClick) {
            host.beginOrExpandSelection(slot);
            host.refreshAll();
            return;
        }

        if (host.selectionActive()) {
            host.dropSelection();
            return;
        }

        if (rightClick) {
            removeNote(slot, music, note);
            return;
        }

        if (isExtendedOctave && !canEditExtendedOctave) {
            Player player = host.player();
            MessageUtils.send(player, Lang.EXTENDED_OCTAVE_DISABLED);
            MessageUtils.send(player, Lang.EXTENDED_OCTAVE_RANGE, "{note}", MusicNote.getNoteName(pitch));
            MessageUtils.send(player, Lang.EXTENDED_OCTAVE_CONFIG);
            return;
        }

        if (note == null) {
            addNote(slot, music, pitch, tick);
            return;
        }

        if (note.getPitch() > host.defaultMaxPitch() && !canEditExtendedOctave) {
            MessageUtils.send(host.player(), Lang.EDIT_NOTE_EXTENDED_OCTAVE_ONLY_DELETE);
            return;
        }
        host.selectNote(note);
        host.enterInstrumentSelectMode();
        host.refreshHotbar();
    }

    private void removeNote(int slot, PlayerMusic music, MusicNote note) {
        if (note == null) {
            return;
        }
        host.pushEditAction(EditAction.removeNote(note));
        music.removeNote(note);
        host.markEdited();
        // Only this cell changed: adding or removing a note does not alter how any other cell
        // renders (the playing-tick highlight depends on the tick, not on the note). Passing the
        // slot takes the incremental path, instead of rebuilding all 54 edit-area items.
        host.refreshSlots(java.util.Collections.singleton(slot));
    }

    private void addNote(int slot, PlayerMusic music, int pitch, int tick) {
        MusicNote note = new MusicNote(pitch, tick);
        note.addInstrument(host.currentInstrument());
        music.addNote(note);
        host.pushEditAction(EditAction.addNote(note));
        host.markEdited();
        // Incremental, for the same reason as the removal above.
        host.refreshSlots(java.util.Collections.singleton(slot));
        host.previewNote(pitch, host.currentInstrument());
    }
}
