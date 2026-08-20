package com.huidu.musicboxplus.module.edit.publish;

import com.huidu.musicboxplus.module.edit.MusicNote;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;

public class PublishedMusic {

    private UUID uniqueId;
    private UUID originalMusicId;
    private String name;
    private String author;
    private UUID authorUUID;
    private String description;
    private double price;
    private int salesCount;
    private double totalRevenue;
    private long publishedAt;
    private long updatedAt;
    private boolean available;
    // Review state: PENDING waits for an admin decision, APPROVED is on sale, REJECTED can be
    // resubmitted by the author. Old files without a status key default to APPROVED.
    private PublishedMusicStatus status = PublishedMusicStatus.APPROVED;
    private PlayerMusic.TimeSignature timeSignature;
    private int bpm;
    private int beatSubdivision;
    private List<MusicNote> notes;
    private Set<UUID> authorClaims;

    public PublishedMusic(PlayerMusic music, double price) {
        this.uniqueId = UUID.randomUUID();
        this.originalMusicId = music.getUniqueId();
        this.name = music.getName();
        this.author = music.getAuthor();
        this.authorUUID = music.getAuthorUUID();
        this.description = music.getDescription();
        this.price = price;
        this.salesCount = 0;
        this.totalRevenue = 0;
        this.publishedAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.available = true;
        this.timeSignature = music.getTimeSignature();
        this.bpm = music.getBpm();
        this.beatSubdivision = music.getBeatSubdivision();
        this.notes = new ArrayList<>(music.getNotes());
        this.authorClaims = new HashSet<>();
    }

    public PublishedMusic() {
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public UUID getOriginalMusicId() {
        return originalMusicId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getAuthor() {
        return author;
    }

    public UUID getAuthorUUID() {
        return authorUUID;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.updatedAt = System.currentTimeMillis();
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
        this.updatedAt = System.currentTimeMillis();
    }

    public int getSalesCount() {
        return salesCount;
    }

    // synchronized: one listing is bought by players on different region threads (Folia) at the
    // same time, so the read-modify-write on salesCount/totalRevenue must not interleave.
    public synchronized void addSale() {
        this.salesCount++;
    }

    public double getTotalRevenue() {
        return totalRevenue;
    }

    public synchronized void addRevenue(double amount) {
        this.totalRevenue += amount;
    }

    public synchronized void rollbackSale(double revenueAmount) {
        if (this.salesCount > 0) {
            this.salesCount--;
        }
        this.totalRevenue = Math.max(0, this.totalRevenue - Math.max(0, revenueAmount));
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
        this.updatedAt = System.currentTimeMillis();
    }

    // A listing is on sale only when the author published it AND an admin approved it.
    public boolean isApproved() {
        return status == PublishedMusicStatus.APPROVED;
    }

    public boolean isPending() {
        return status == PublishedMusicStatus.PENDING;
    }

    public PublishedMusicStatus getStatus() {
        return status;
    }

    public void setStatus(PublishedMusicStatus status) {
        if (status == null) {
            return;
        }
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    public enum PublishedMusicStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public PlayerMusic.TimeSignature getTimeSignature() {
        return timeSignature;
    }

    public int getBpm() {
        return bpm;
    }

    public int getBeatSubdivision() {
        return beatSubdivision;
    }

    public List<MusicNote> getNotes() {
        return notes;
    }

    public int getNoteCount() {
        return notes != null ? notes.size() : 0;
    }

    public boolean hasAuthorClaim(UUID playerUUID) {
        return playerUUID != null && authorClaims != null && authorClaims.contains(playerUUID);
    }

    public void addAuthorClaim(UUID playerUUID) {
        if (playerUUID == null) {
            return;
        }
        if (authorClaims == null) {
            authorClaims = new HashSet<>();
        }
        authorClaims.add(playerUUID);
        this.updatedAt = System.currentTimeMillis();
    }

    public void removeAuthorClaim(UUID playerUUID) {
        if (playerUUID == null || authorClaims == null) {
            return;
        }
        if (authorClaims.remove(playerUUID)) {
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public void updateFromMusic(PlayerMusic music) {
        this.name = music.getName();
        this.description = music.getDescription();
        this.timeSignature = music.getTimeSignature();
        this.bpm = music.getBpm();
        this.beatSubdivision = music.getBeatSubdivision();
        this.notes = new ArrayList<>(music.getNotes());
        this.updatedAt = System.currentTimeMillis();
    }

    public PlayerMusic toPlayerMusic() {
        PlayerMusic music = new PlayerMusic(name, author, authorUUID);
        copyTo(music);
        return music;
    }

    public PlayerMusic toPlayerMusic(String ownerName, UUID ownerUUID) {
        PlayerMusic music = new PlayerMusic(name, ownerName, ownerUUID);
        copyTo(music);
        return music;
    }

    private void copyTo(PlayerMusic music) {
        music.setTimeSignature(timeSignature);
        music.setBpm(bpm);
        music.setBeatSubdivision(beatSubdivision);
        music.setDescription(description);
        for (MusicNote note : notes) {
            music.addNote(new MusicNote(note.getPitch(), note.getTick(), new ArrayList<>(note.getInstruments())));
        }
    }

    public static PublishedMusic fromConfig(YamlConfiguration config) {
        PublishedMusic published = new PublishedMusic();
        
        String idStr = config.getString("id");
        if (idStr != null) {
            published.uniqueId = UUID.fromString(idStr);
        }
        
        String originalIdStr = config.getString("originalMusicId");
        if (originalIdStr != null) {
            published.originalMusicId = UUID.fromString(originalIdStr);
        }
        
        published.name = config.getString("name", "Untitled");
        published.author = config.getString("author", "Unknown");
        
        String authorUUIDStr = config.getString("authorUUID");
        if (authorUUIDStr != null) {
            published.authorUUID = UUID.fromString(authorUUIDStr);
        }
        
        published.description = config.getString("description", "");
        published.price = config.getDouble("price", 0);
        published.salesCount = config.getInt("salesCount", 0);
        published.totalRevenue = config.getDouble("totalRevenue", 0);
        published.publishedAt = config.getLong("publishedAt", System.currentTimeMillis());
        published.updatedAt = config.getLong("updatedAt", System.currentTimeMillis());
        published.available = config.getBoolean("available", true);
        // Files written before the approval flow have no status key; they were on sale
        // without review, so APPROVED is the safe legacy default.
        String statusStr = config.getString("status");
        if (statusStr != null) {
            try {
                published.status = PublishedMusicStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                // A hand-edited file with a corrupted status must not go on sale silently;
                // log it so the admin can fix the value. APPROVED (the field default) remains
                // the fallback, matching the legacy default above.
                com.huidu.musicboxplus.MusicBox.getInstance().getLogger().warning(
                        "Published music '" + published.name + "' has invalid status '" + statusStr
                                + "', defaulting to APPROVED");
            }
        }
        published.timeSignature = PlayerMusic.TimeSignature.fromString(config.getString("timeSignature", "4/4"));
        published.bpm = config.getInt("bpm", 120);
        published.beatSubdivision = config.getInt("beatSubdivision", 4);
        published.authorClaims = new HashSet<>();
        for (String claim : config.getStringList("authorClaims")) {
            try {
                published.authorClaims.add(UUID.fromString(claim));
            } catch (IllegalArgumentException ignored) {
            }
        }
        
        published.notes = new ArrayList<>();
        ConfigurationSection notesSection = config.getConfigurationSection("notes");
        if (notesSection != null) {
            for (String key : notesSection.getKeys(false)) {
                ConfigurationSection noteSection = notesSection.getConfigurationSection(key);
                if (noteSection != null) {
                    int pitch = noteSection.getInt("pitch");
                    int tick = noteSection.getInt("tick");
                    List<String> instrumentNames = noteSection.getStringList("instruments");
                    List<MusicNote.NoteInstrument> instruments = new ArrayList<>();
                    for (String instrName : instrumentNames) {
                        MusicNote.NoteInstrument instrument = MusicNote.NoteInstrument.parseStored(instrName);
                        if (instrument != null) {
                            instruments.add(instrument);
                        }
                    }
                    if (!instruments.isEmpty()) {
                        published.notes.add(new MusicNote(pitch, tick, instruments));
                    }
                }
            }
        }
        
        return published;
    }

    public YamlConfiguration toConfig() {
        YamlConfiguration config = new YamlConfiguration();
        
        config.set("id", uniqueId.toString());
        config.set("originalMusicId", originalMusicId != null ? originalMusicId.toString() : null);
        config.set("name", name);
        config.set("author", author);
        config.set("authorUUID", authorUUID != null ? authorUUID.toString() : null);
        config.set("description", description);
        config.set("price", price);
        config.set("salesCount", salesCount);
        config.set("totalRevenue", totalRevenue);
        config.set("publishedAt", publishedAt);
        config.set("updatedAt", updatedAt);
        config.set("available", available);
        config.set("status", status.name());
        config.set("timeSignature", timeSignature.toString());
        config.set("bpm", bpm);
        config.set("beatSubdivision", beatSubdivision);
        List<String> claimList = new ArrayList<>();
        if (authorClaims != null) {
            for (UUID claim : authorClaims) {
                claimList.add(claim.toString());
            }
        }
        config.set("authorClaims", claimList);
        
        int noteIndex = 0;
        for (MusicNote note : notes) {
            String path = "notes.note" + noteIndex;
            config.set(path + ".pitch", note.getPitch());
            config.set(path + ".tick", note.getTick());
            List<String> instrumentNames = new ArrayList<>();
            for (MusicNote.NoteInstrument instr : note.getInstruments()) {
                instrumentNames.add(instr.name());
            }
            config.set(path + ".instruments", instrumentNames);
            noteIndex++;
        }
        
        return config;
    }
}
