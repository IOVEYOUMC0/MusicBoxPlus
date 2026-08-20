package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoteClipboard {

    private static final Map<UUID, NoteClipboard> playerClipboards = new ConcurrentHashMap<>();
    
    private List<EditAction.NoteData> copiedNotes = new ArrayList<>();
    private int minTick = 0;
    private int minPitch = 0;

    private NoteClipboard() {
    }

    public static NoteClipboard getInstance(UUID playerUUID) {
        return playerClipboards.computeIfAbsent(playerUUID, k -> new NoteClipboard());
    }
    
    public static NoteClipboard getInstance() {
        return getInstance(UUID.randomUUID());
    }

    public void copy(List<MusicNote> notes) {
        copiedNotes = new ArrayList<>();
        if (notes.isEmpty()) {
            return;
        }
        
        minTick = Integer.MAX_VALUE;
        minPitch = Integer.MAX_VALUE;
        
        for (MusicNote note : notes) {
            copiedNotes.add(new EditAction.NoteData(note));
            minTick = Math.min(minTick, note.getTick());
            minPitch = Math.min(minPitch, note.getPitch());
        }
    }

    public void copy(MusicNote note) {
        copiedNotes = new ArrayList<>();
        copiedNotes.add(new EditAction.NoteData(note));
        minTick = note.getTick();
        minPitch = note.getPitch();
    }

    public List<EditAction.NoteData> getPastedNotes(int targetTick, int targetPitch) {
        List<EditAction.NoteData> result = new ArrayList<>();
        
        int tickOffset = targetTick - minTick;
        int pitchOffset = targetPitch - minPitch;
        
        int maxPitch = getMaxPitch();
        
        for (EditAction.NoteData data : copiedNotes) {
            int newTick = data.getTick() + tickOffset;
            int newPitch = data.getPitch() + pitchOffset;
            
            if (newPitch >= 0 && newPitch <= maxPitch) {
                result.add(new EditAction.NoteData(newPitch, newTick, data.getInstruments()));
            }
        }
        
        return result;
    }
    
    private int getMaxPitch() {
        try {
            if (MusicBox.getInstance().getConfigObject().isEnable10octave()) {
                return MusicBox.getInstance().getConfigObject().getEditor().getExtendedMaxPitch();
            }
            return MusicBox.getInstance().getConfigObject().getEditor().getDefaultMaxPitch();
        } catch (Exception e) {
            return 24;
        }
    }

    public List<EditAction.NoteData> getCopiedNotes() {
        return new ArrayList<>(copiedNotes);
    }

    public boolean hasContent() {
        return !copiedNotes.isEmpty();
    }

    public int getSize() {
        return copiedNotes.size();
    }

    public void clear() {
        copiedNotes = new ArrayList<>();
        minTick = 0;
        minPitch = 0;
    }
    
    public static void cleanup(UUID playerUUID) {
        playerClipboards.remove(playerUUID);
    }
}
