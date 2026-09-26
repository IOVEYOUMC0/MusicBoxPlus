package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;

import java.util.ArrayList;
import java.util.List;

public class EditHistory {

    private final List<EditAction> history = new ArrayList<>();
    private int currentIndex = -1;

    private int getMaxHistorySize() {
        return MusicBox.getInstance().getConfigObject().getEditor().getMaxHistorySize();
    }

    public void pushAction(EditAction action) {
        while (history.size() > currentIndex + 1) {
            history.remove(history.size() - 1);
        }
        
        if (history.size() >= getMaxHistorySize()) {
            history.remove(0);
            currentIndex--;
        }
        
        history.add(action);
        currentIndex++;
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
    }

    public int getHistorySize() {
        return history.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }
}
