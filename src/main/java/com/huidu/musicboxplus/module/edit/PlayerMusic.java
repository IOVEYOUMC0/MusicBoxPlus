package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerMusic implements com.huidu.musicboxplus.core.song.PlayerMusicSource {

    private static int getDefaultBpm() {
        return MusicBox.getInstance().getConfigObject().getEditor().getDefaultBpm();
    }
    
    private static int getDefaultBeatSubdivision() {
        return MusicBox.getInstance().getConfigObject().getEditor().getDefaultBeatSubdivision();
    }

    public enum TimeSignature {
        ONE_FOUR(1, 4),
        TWO_FOUR(2, 4),
        THREE_FOUR(3, 4),
        FOUR_FOUR(4, 4),
        FIVE_FOUR(5, 4),
        SEVEN_FOUR(7, 4),
        THREE_EIGHT(3, 8),
        SIX_EIGHT(6, 8),
        NINE_EIGHT(9, 8),
        TWELVE_EIGHT(12, 8);

        private final int beatsPerMeasure;
        private final int beatUnit;
        private static final TimeSignature[] VALUES = values();
        private static final Map<String, TimeSignature> STRING_CACHE = new HashMap<>();
        
        static {
            for (TimeSignature ts : VALUES) {
                STRING_CACHE.put(ts.toString(), ts);
            }
        }

        TimeSignature(int beatsPerMeasure, int beatUnit) {
            this.beatsPerMeasure = beatsPerMeasure;
            this.beatUnit = beatUnit;
        }

        public int getBeatsPerMeasure() {
            return beatsPerMeasure;
        }

        public int getBeatUnit() {
            return beatUnit;
        }

        @Override
        public String toString() {
            return beatsPerMeasure + "/" + beatUnit;
        }

        public static TimeSignature fromString(String value) {
            return STRING_CACHE.getOrDefault(value, FOUR_FOUR);
        }
        
        public static TimeSignature[] getAllValues() {
            return VALUES;
        }
    }

    private UUID uniqueId;
    private String name;
    private final String author;
    private final UUID authorUUID;
    private TimeSignature timeSignature;
    private int bpm;
    private int beatSubdivision;
    private final long createdAt;
    private long updatedAt;
    private final List<MusicNote> notes;
    private final Map<String, MusicNote> noteIndexMap;
    private final NavigableMap<Integer, List<MusicNote>> tickIndexMap;
    private String description;
    private int cachedMaxTick = -1;

    public PlayerMusic(String name, String author, UUID authorUUID) {
        this.uniqueId = UUID.randomUUID();
        this.name = name;
        this.author = author;
        this.authorUUID = authorUUID;
        this.timeSignature = TimeSignature.FOUR_FOUR;
        this.bpm = getDefaultBpm();
        this.beatSubdivision = getDefaultBeatSubdivision();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.notes = Collections.synchronizedList(new ArrayList<>());
        this.noteIndexMap = Collections.synchronizedMap(new HashMap<>());
        this.tickIndexMap = Collections.synchronizedNavigableMap(new TreeMap<>());
        this.description = "";
    }

    public PlayerMusic(UUID uniqueId, String name, String author, UUID authorUUID,
                       TimeSignature timeSignature, int bpm, int beatSubdivision,
                       long createdAt, long updatedAt, List<MusicNote> notes, String description) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.author = author;
        this.authorUUID = authorUUID;
        this.timeSignature = timeSignature;
        this.bpm = bpm;
        this.beatSubdivision = beatSubdivision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.notes = Collections.synchronizedList(new ArrayList<>(notes));
        this.noteIndexMap = Collections.synchronizedMap(new HashMap<>());
        this.tickIndexMap = Collections.synchronizedNavigableMap(new TreeMap<>());
        for (MusicNote note : this.notes) {
            String key = createNoteKey(note.getPitch(), note.getTick());
            this.noteIndexMap.put(key, note);
            this.tickIndexMap.computeIfAbsent(note.getTick(), k -> new CopyOnWriteArrayList<>()).add(note);
        }
        this.description = description != null ? description : "";
    }

    private String createNoteKey(int pitch, int tick) {
        return pitch + "_" + tick;
    }

    public synchronized UUID getUniqueId() {
        return uniqueId;
    }

    public synchronized String getName() {
        return name;
    }

    public synchronized void setName(String name) {
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getAuthor() {
        return author;
    }

    public UUID getAuthorUUID() {
        return authorUUID;
    }

    public synchronized TimeSignature getTimeSignature() {
        return timeSignature;
    }

    public synchronized void setTimeSignature(TimeSignature timeSignature) {
        this.timeSignature = timeSignature;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized int getBpm() {
        return bpm;
    }

    public synchronized void setBpm(int bpm) {
        int min = MusicBox.getInstance().getConfigObject().getEditor().getMinBpm();
        int max = MusicBox.getInstance().getConfigObject().getEditor().getMaxBpm();
        this.bpm = Math.max(min, Math.min(max, bpm));
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized int getBeatSubdivision() {
        return beatSubdivision;
    }

    public synchronized void setBeatSubdivision(int beatSubdivision) {
        this.beatSubdivision = Math.max(1, Math.min(16, beatSubdivision));
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public synchronized long getUpdatedAt() {
        return updatedAt;
    }

    public synchronized void updateTimestamp() {
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized String getDescription() {
        return description;
    }

    public synchronized void setDescription(String description) {
        this.description = description;
        this.updatedAt = System.currentTimeMillis();
    }

    public synchronized boolean addNote(MusicNote note) {
        String key = createNoteKey(note.getPitch(), note.getTick());
        if (noteIndexMap.containsKey(key)) {
            return false;
        }
        boolean added = notes.add(note);
        if (added) {
            noteIndexMap.put(key, note);
            tickIndexMap.computeIfAbsent(note.getTick(), k -> new CopyOnWriteArrayList<>()).add(note);
            cachedMaxTick = -1;
            updateTimestamp();
        }
        return added;
    }

    public synchronized boolean removeNote(MusicNote note) {
        String key = createNoteKey(note.getPitch(), note.getTick());
        boolean removed = notes.remove(note);
        if (removed) {
            noteIndexMap.remove(key);
            List<MusicNote> tickNotes = tickIndexMap.get(note.getTick());
            if (tickNotes != null) {
                tickNotes.remove(note);
                if (tickNotes.isEmpty()) {
                    tickIndexMap.remove(note.getTick());
                }
            }
            cachedMaxTick = -1;
            updateTimestamp();
        }
        return removed;
    }

    public synchronized MusicNote getNote(int pitch, int tick) {
        return noteIndexMap.get(createNoteKey(pitch, tick));
    }

    public synchronized List<MusicNote> getNotesAtTick(int tick) {
        List<MusicNote> tickNotes = tickIndexMap.get(tick);
        return tickNotes != null ? new ArrayList<>(tickNotes) : Collections.emptyList();
    }


    public synchronized List<MusicNote> getNotes() {
        return new ArrayList<>(notes);
    }
    
    public synchronized List<MusicNote> getNotesSortedByTick() {
        List<MusicNote> sorted = new ArrayList<>(notes);
        sorted.sort((n1, n2) -> Integer.compare(n1.getTick(), n2.getTick()));
        return sorted;
    }
    
    public synchronized NavigableMap<Integer, List<MusicNote>> getTickIndexMap() {
        return new TreeMap<>(tickIndexMap);
    }

    public synchronized void clearNotes() {
        notes.clear();
        noteIndexMap.clear();
        tickIndexMap.clear();
        cachedMaxTick = -1;
        updateTimestamp();
    }

    public synchronized int getNoteCount() {
        return notes.size();
    }

    public synchronized int getMaxTick() {
        if (cachedMaxTick >= 0) {
            return cachedMaxTick;
        }
        if (tickIndexMap.isEmpty()) {
            cachedMaxTick = 0;
        } else {
            cachedMaxTick = tickIndexMap.lastKey();
        }
        return cachedMaxTick;
    }

    // Metadata copy with no notes, for the caller that is about to replace the note set anyway.
    // Copying every note into the new index and then clearing it is the whole cost of a snapshot
    // paid twice for nothing.
    public synchronized PlayerMusic snapshotWithoutNotes() {
        return new PlayerMusic(uniqueId, name, author, authorUUID, timeSignature, bpm,
                beatSubdivision, createdAt, updatedAt, List.of(), description);
    }

    public synchronized PlayerMusic snapshot() {
        return new PlayerMusic(
            uniqueId,
            name,
            author,
            authorUUID,
            timeSignature,
            bpm,
            beatSubdivision,
            createdAt,
            updatedAt,
            new ArrayList<>(notes),
            description
        );
    }

    public synchronized void applySnapshot(PlayerMusic snapshot) {
        this.uniqueId = snapshot.getUniqueId();
        this.name = snapshot.getName();
        this.timeSignature = snapshot.getTimeSignature();
        this.bpm = snapshot.getBpm();
        this.beatSubdivision = snapshot.getBeatSubdivision();
        this.updatedAt = snapshot.getUpdatedAt();
        this.description = snapshot.getDescription();
        this.notes.clear();
        this.noteIndexMap.clear();
        this.tickIndexMap.clear();
        for (MusicNote note : snapshot.getNotes()) {
            this.notes.add(note);
            String key = createNoteKey(note.getPitch(), note.getTick());
            this.noteIndexMap.put(key, note);
            this.tickIndexMap.computeIfAbsent(note.getTick(), k -> new CopyOnWriteArrayList<>()).add(note);
        }
        this.cachedMaxTick = -1;
    }

    public synchronized void setUniqueId(UUID uniqueId) {
        this.uniqueId = uniqueId;
    }
}
