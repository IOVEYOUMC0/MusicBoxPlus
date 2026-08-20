package com.huidu.musicboxplus.module.edit;

import java.util.ArrayList;
import java.util.List;

public class EditAction {

    public enum ActionType {
        ADD_NOTE,
        REMOVE_NOTE,
        MODIFY_NOTE,
        BATCH_ADD,
        BATCH_REMOVE,
        CLEAR_ALL
    }

    private final ActionType type;
    private final List<NoteData> oldNotes;
    private final List<NoteData> newNotes;

    public EditAction(ActionType type) {
        this.type = type;
        this.oldNotes = new ArrayList<>();
        this.newNotes = new ArrayList<>();
    }

    public static EditAction addNote(MusicNote note) {
        EditAction action = new EditAction(ActionType.ADD_NOTE);
        action.newNotes.add(new NoteData(note));
        return action;
    }

    public static EditAction removeNote(MusicNote note) {
        EditAction action = new EditAction(ActionType.REMOVE_NOTE);
        action.oldNotes.add(new NoteData(note));
        return action;
    }

    public static EditAction modifyNote(MusicNote oldNote, MusicNote newNote) {
        EditAction action = new EditAction(ActionType.MODIFY_NOTE);
        action.oldNotes.add(new NoteData(oldNote));
        action.newNotes.add(new NoteData(newNote));
        return action;
    }

    public static EditAction batchAdd(List<MusicNote> notes) {
        EditAction action = new EditAction(ActionType.BATCH_ADD);
        for (MusicNote note : notes) {
            action.newNotes.add(new NoteData(note));
        }
        return action;
    }

    public static EditAction batchRemove(List<MusicNote> notes) {
        EditAction action = new EditAction(ActionType.BATCH_REMOVE);
        for (MusicNote note : notes) {
            action.oldNotes.add(new NoteData(note));
        }
        return action;
    }

    public static EditAction batchModify(List<MusicNote> notes, MusicNote.NoteInstrument targetInstrument) {
        EditAction action = new EditAction(ActionType.MODIFY_NOTE);
        for (MusicNote note : notes) {
            action.oldNotes.add(new NoteData(note));
            List<MusicNote.NoteInstrument> targetInstruments = new ArrayList<>();
            targetInstruments.add(targetInstrument);
            action.newNotes.add(new NoteData(note.getPitch(), note.getTick(), targetInstruments));
        }
        return action;
    }

    public static EditAction clearAll(List<MusicNote> notes) {
        EditAction action = new EditAction(ActionType.CLEAR_ALL);
        for (MusicNote note : notes) {
            action.oldNotes.add(new NoteData(note));
        }
        return action;
    }

    public ActionType getType() {
        return type;
    }

    public List<NoteData> getOldNotes() {
        return oldNotes;
    }

    public List<NoteData> getNewNotes() {
        return newNotes;
    }

    public void addOldNote(MusicNote note) {
        oldNotes.add(new NoteData(note));
    }

    public void addNewNote(MusicNote note) {
        newNotes.add(new NoteData(note));
    }

    public static class NoteData {
        private final int pitch;
        private final int tick;
        private final List<MusicNote.NoteInstrument> instruments;

        public NoteData(MusicNote note) {
            this.pitch = note.getPitch();
            this.tick = note.getTick();
            this.instruments = new ArrayList<>(note.getInstruments());
        }

        public NoteData(int pitch, int tick, List<MusicNote.NoteInstrument> instruments) {
            this.pitch = pitch;
            this.tick = tick;
            this.instruments = new ArrayList<>(instruments);
        }

        public int getPitch() {
            return pitch;
        }

        public int getTick() {
            return tick;
        }

        public List<MusicNote.NoteInstrument> getInstruments() {
            return instruments;
        }

        public MusicNote createNote() {
            MusicNote note = new MusicNote(pitch, tick);
            for (MusicNote.NoteInstrument instrument : instruments) {
                note.addInstrument(instrument);
            }
            return note;
        }
    }
}
