package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

public class EditHistory {

    // How much note data the undo stack may retain, in addition to the entry-count cap.
    //
    // The count alone was not a budget: one CLEAR_ALL action carries a NoteData per note of the whole
    // song, so clearing a 100k-note song pinned 6-10 MB until 50 more edits pushed it out -- and the
    // editor's autosave means a player can leave that sitting there for a whole session. The oldest
    // action is dropped once the total crosses this, which keeps the recent edits (the ones a player
    // actually undoes) and bounds the stack regardless of what the actions contain.
    static final long MAX_HISTORY_BYTES = 8L * 1024 * 1024;

    private final List<EditAction> history = new ArrayList<>();
    private final IntSupplier maxHistorySize;
    private final long maxHistoryBytes;
    private int currentIndex = -1;
    private long retainedBytes;

    public EditHistory() {
        this(() -> MusicBox.getInstance().getConfigObject().getEditor().getMaxHistorySize(),
                MAX_HISTORY_BYTES);
    }

    // Both limits injectable so the trimming rules can be tested without a server: the default
    // constructor reads the entry cap from the config, which needs a live plugin.
    EditHistory(IntSupplier maxHistorySize, long maxHistoryBytes) {
        this.maxHistorySize = maxHistorySize;
        this.maxHistoryBytes = maxHistoryBytes;
    }

    EditHistory(int fixedMaxHistorySize, long maxHistoryBytes) {
        this(() -> fixedMaxHistorySize, maxHistoryBytes);
    }

    public void pushAction(EditAction action) {
        while (history.size() > currentIndex + 1) {
            retainedBytes -= estimateBytes(history.remove(history.size() - 1));
        }

        if (history.size() >= maxHistorySize.getAsInt()) {
            retainedBytes -= estimateBytes(history.remove(0));
            currentIndex--;
        }

        history.add(action);
        retainedBytes += estimateBytes(action);
        currentIndex++;

        // After trimming by count: one oversized action (clear-all on a long song) can exceed the
        // whole budget by itself, and dropping it immediately would make undo useless, so it is kept
        // as the single entry it is.
        while (history.size() > 1 && retainedBytes > maxHistoryBytes) {
            retainedBytes -= estimateBytes(history.remove(0));
            currentIndex--;
        }
    }

    /**
     * Rough retained size of an action: the action and its two lists, plus one NoteData and its
     * instrument list per note.
     *
     * An estimate on purpose -- the point is to notice an action that is a thousand times larger
     * than the others (a clear-all), not to measure the heap. Object headers and array overhead are
     * rounded into the constants.
     */
    static long estimateBytes(EditAction action) {
        if (action == null) {
            return 0;
        }
        return 64 + estimateBytes(action.getOldNotes()) + estimateBytes(action.getNewNotes());
    }

    private static long estimateBytes(List<EditAction.NoteData> notes) {
        long bytes = 0;
        for (EditAction.NoteData note : notes) {
            bytes += 48 + 8L * note.getInstruments().size();
        }
        return bytes;
    }

    public EditAction undo() {
        if (currentIndex < 0) {
            return null;
        }
        EditAction action = history.get(currentIndex);
        currentIndex--;
        return action;
    }

    public EditAction redo() {
        if (currentIndex >= history.size() - 1) {
            return null;
        }
        currentIndex++;
        return history.get(currentIndex);
    }

    public boolean canUndo() {
        return currentIndex >= 0;
    }

    public boolean canRedo() {
        return currentIndex < history.size() - 1;
    }

    public void clear() {
        history.clear();
        currentIndex = -1;
        retainedBytes = 0;
    }

    /** Estimated retained size of the actions still on the stack. See {@link #estimateBytes}. */
    long getRetainedBytes() {
        return retainedBytes;
    }

    public int getHistorySize() {
        return history.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }
}
