package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.core.db.AbstractBase;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.utils.ResultSetRow;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PlayerMusicManager {

    private static volatile PlayerMusicManager instance;
    private static final Object LOCK = new Object();
    private final Map<UUID, List<PlayerMusic>> playerMusicCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMusic> musicByIdCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMusic> pendingSaveSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingSaveSignatures = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastSavedSignatures = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Boolean>> saveChains = new ConcurrentHashMap<>();

    public static PlayerMusicManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new PlayerMusicManager();
                }
            }
        }
        return instance;
    }

    public static PlayerMusicManager getExistingInstance() {
        return instance;
    }

    private PlayerMusicManager() {
    }

    private void cacheMusic(PlayerMusic music) {
        if (music == null) {
            return;
        }

        UUID musicId = music.getUniqueId();
        UUID authorUUID = music.getAuthorUUID();
        musicByIdCache.put(musicId, music);
        // Rebuild any disc adapter so edits/renames are reflected on player-music discs.
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);

        List<PlayerMusic> playerMusicList = playerMusicCache.computeIfAbsent(
                authorUUID, k -> new CopyOnWriteArrayList<>());
        playerMusicList.removeIf(existing -> existing.getUniqueId().equals(musicId));
        playerMusicList.add(music);
    }

    public void loadAllMusic() {
        if (!DatabaseLoader.isInitialized()) {
            MusicBox.getInstance().getLogger().warning("Database is not initialized, cannot load player music");
            return;
        }

        // Build into temp maps and commit only on a fully successful query. Clearing the
        // caches up front then failing (e.g. a DB hiccup during /reload) left them empty
        // but authoritative -> players could exceed their music limit, lose dup-name
        // protection, and not see their real catalog. Keep the old cache on failure.
        Map<UUID, PlayerMusic> loadedById = new HashMap<>();
        Map<UUID, List<PlayerMusic>> loadedByPlayer = new HashMap<>();
        try {
            AbstractBase base = DatabaseLoader.getBase();
            List<ResultSetRow> musicRows = base.executeQuery(
                "SELECT id, name, author, author_uuid, time_signature, bpm, beat_subdivision, created_at, updated_at, description FROM player_music"
            );

            Map<String, List<ResultSetRow>> notesMap = new HashMap<>();
            List<ResultSetRow> noteRows = base.executeQuery(
                "SELECT music_id, pitch, tick, instruments FROM player_music_notes"
            );
            for (ResultSetRow noteRow : noteRows) {
                String musicId = noteRow.getString("music_id");
                notesMap.computeIfAbsent(musicId, k -> new ArrayList<>()).add(noteRow);
            }

            for (ResultSetRow row : musicRows) {
                try {
                    String idStr = row.getString("id");
                    UUID uniqueId = UUID.fromString(idStr);
                    String name = row.getString("name");
                    String author = row.getString("author");
                    String authorUuidStr = row.getString("author_uuid");
                    UUID authorUUID = UUID.fromString(authorUuidStr);
                    String timeSignatureStr = row.getString("time_signature");
                    PlayerMusic.TimeSignature timeSignature = PlayerMusic.TimeSignature.fromString(timeSignatureStr);
                    int bpm = row.getInt("bpm");
                    int beatSubdivision = row.getInt("beat_subdivision");
                    long createdAt = row.getLong("created_at");
                    long updatedAt = row.getLong("updated_at");
                    String description = row.getString("description");
                    if (description == null) description = "";

                    List<MusicNote> notes = new ArrayList<>();
                    List<ResultSetRow> noteList = notesMap.get(idStr);
                    if (noteList != null) {
                        for (ResultSetRow noteRow : noteList) {
                            int pitch = noteRow.getInt("pitch");
                            int tick = noteRow.getInt("tick");
                            String instrumentsStr = noteRow.getString("instruments");
                            List<MusicNote.NoteInstrument> instruments = Arrays.stream(instrumentsStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(this::parseInstrument)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                            notes.add(new MusicNote(pitch, tick, instruments));
                        }
                    }

                    PlayerMusic music = new PlayerMusic(uniqueId, name, author, authorUUID, timeSignature, bpm,
                            beatSubdivision, createdAt, updatedAt, notes, description);

                    loadedById.put(uniqueId, music);
                    loadedByPlayer.computeIfAbsent(authorUUID, k -> new ArrayList<>()).add(music);
                } catch (Exception e) {
                    MusicBox.getInstance().getLogger().log(Level.WARNING, com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Failed to load player music record: " + e.getMessage(), "加载玩家音乐记录失败: " + e.getMessage()), e);
                }
            }
        } catch (Exception e) {
            MusicBox.getInstance().getLogger().log(Level.SEVERE, com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Failed to load player music (keeping existing cache)", "加载玩家音乐失败（保留现有缓存）"), e);
            return;
        }

        // Query succeeded — atomically replace the caches.
        musicByIdCache.clear();
        musicByIdCache.putAll(loadedById);
        playerMusicCache.clear();
        loadedByPlayer.forEach((uuid, list) -> playerMusicCache.put(uuid, new CopyOnWriteArrayList<>(list)));

        MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Loaded " + musicByIdCache.size() + " player music entries", "已加载 " + musicByIdCache.size() + " 条玩家音乐记录"));
    }

    private MusicNote.NoteInstrument parseInstrument(String name) {
        return MusicNote.NoteInstrument.parseStored(name);
    }

    public boolean saveMusic(PlayerMusic music) {
        cacheMusic(music);

        saveMusicAsync(music).exceptionally(ex -> {
            MusicBox.getInstance().getLogger().log(Level.SEVERE, "Failed to save player music asynchronously: " + music.getName(), ex);
            return false;
        });
        return true;
    }

    public void stagePendingSave(PlayerMusic music) {
        if (music == null) {
            return;
        }
        cacheMusic(music);

        PlayerMusic snapshot = music.snapshot();
        pendingSaveSnapshots.put(snapshot.getUniqueId(), snapshot);
        pendingSaveSignatures.put(snapshot.getUniqueId(), calculateSnapshotSignature(snapshot));
    }

    public CompletableFuture<Boolean> saveMusicAsync(PlayerMusic music) {
        cacheMusic(music);
        PlayerMusic snapshot = music.snapshot();
        UUID musicId = snapshot.getUniqueId();
        int snapshotSignature = calculateSnapshotSignature(snapshot);
        pendingSaveSnapshots.put(musicId, snapshot);
        pendingSaveSignatures.put(musicId, snapshotSignature);
        if (Objects.equals(lastSavedSignatures.get(musicId), snapshotSignature)) {
            pendingSaveSnapshots.remove(musicId, snapshot);
            pendingSaveSignatures.remove(musicId, snapshotSignature);
            return CompletableFuture.completedFuture(true);
        }
        if (!MusicBox.getInstance().isEnabled()) {
            boolean saved = saveMusicSyncInternal(snapshot);
            if (saved) {
                pendingSaveSnapshots.remove(musicId, snapshot);
                pendingSaveSignatures.remove(musicId, snapshotSignature);
                lastSavedSignatures.put(musicId, snapshotSignature);
            }
            return CompletableFuture.completedFuture(saved);
        }
        CompletableFuture<Boolean> previous = saveChains.get(musicId);
        if (previous == null) {
            previous = CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> next = previous.handle((ignored, throwable) -> null)
                .thenCompose(ignored -> persistLatestSnapshotAsync(musicId));
        saveChains.put(musicId, next);
        next.whenComplete((result, throwable) -> saveChains.remove(musicId, next));
        return next;
    }

    public void saveMusicAsync(PlayerMusic music, Consumer<Boolean> callback) {
        // Resolve the owning player up front: the author of the edited music is the player
        // whose inventory/sounds/GUI the callback touches.
        UUID ownerId = music != null ? music.getAuthorUUID() : null;
        saveMusicAsync(music).whenComplete((saved, throwable) -> {
            boolean success = throwable == null && Boolean.TRUE.equals(saved);
            if (callback == null) {
                return;
            }
            if (!MusicBox.getInstance().isEnabled()) {
                callback.accept(success);
                return;
            }
            // Folia: the callback opens/refreshes inventories, plays sounds and sends
            // messages to the owning player, so it must run on that player's region rather
            // than the global region thread. Fall back to the global region only when the
            // player can't be resolved (offline/unknown), preserving the previous behaviour.
            Player owner = ownerId != null ? Bukkit.getPlayer(ownerId) : null;
            if (owner != null) {
                Scheduler.entity(owner, () -> callback.accept(success));
            } else {
                Scheduler.global(() -> callback.accept(success));
            }
        });
    }

    private CompletableFuture<Boolean> persistLatestSnapshotAsync(UUID musicId) {
        PlayerMusic snapshot = pendingSaveSnapshots.get(musicId);
        Integer signature = pendingSaveSignatures.get(musicId);
        if (snapshot == null || signature == null) {
            return CompletableFuture.completedFuture(true);
        }
        if (Objects.equals(lastSavedSignatures.get(musicId), signature)) {
            pendingSaveSnapshots.remove(musicId, snapshot);
            pendingSaveSignatures.remove(musicId, signature);
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(() -> {
            try {
                future.complete(persistSnapshotSync(musicId, snapshot, signature));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, () -> future.complete(false));
        return future;
    }

    private boolean persistSnapshotSync(UUID musicId, PlayerMusic snapshot, int signature) {
        // Share the delete's monitor so a save already in flight when the delete ran sees the
        // cache entry gone and skips the upsert, instead of writing the stale row back and
        // resurrecting the deleted music.
        synchronized (this) {
            if (musicByIdCache.get(musicId) == null) {
                pendingSaveSnapshots.remove(musicId, snapshot);
                pendingSaveSignatures.remove(musicId, signature);
                return true;
            }
            if (Objects.equals(lastSavedSignatures.get(musicId), signature)) {
                pendingSaveSnapshots.remove(musicId, snapshot);
                pendingSaveSignatures.remove(musicId, signature);
                return true;
            }

            boolean saved = saveMusicSyncInternal(snapshot);
            if (!saved) {
                pendingSaveSnapshots.put(musicId, snapshot);
                pendingSaveSignatures.put(musicId, signature);
                return false;
            }

            lastSavedSignatures.put(musicId, signature);
            pendingSaveSnapshots.remove(musicId, snapshot);
            pendingSaveSignatures.remove(musicId, signature);

            PlayerMusic newerSnapshot = pendingSaveSnapshots.get(musicId);
            Integer newerSignature = pendingSaveSignatures.get(musicId);
            if (newerSnapshot != null && newerSignature != null && newerSignature != signature) {
                return persistSnapshotSync(musicId, newerSnapshot, newerSignature);
            }
            return true;
        }
    }

    private int calculateSnapshotSignature(PlayerMusic music) {
        int result = Objects.hash(
                music.getUniqueId(),
                music.getName(),
                music.getAuthor(),
                music.getAuthorUUID(),
                music.getTimeSignature(),
                music.getBpm(),
                music.getBeatSubdivision(),
                music.getDescription(),
                music.getCreatedAt()
        );
        for (MusicNote note : music.getNotesSortedByTick()) {
            result = 31 * result + note.getPitch();
            result = 31 * result + note.getTick();
            result = 31 * result + note.getInstruments().hashCode();
        }
        return result;
    }

    public boolean saveMusicSync(PlayerMusic music) {
        cacheMusic(music);

        PlayerMusic snapshot = music.snapshot();
        int snapshotSignature = calculateSnapshotSignature(snapshot);
        boolean saved = persistSnapshotSync(snapshot.getUniqueId(), snapshot, snapshotSignature);
        if (saved) {
            pendingSaveSnapshots.remove(snapshot.getUniqueId());
            pendingSaveSignatures.remove(snapshot.getUniqueId());
        } else {
            pendingSaveSnapshots.put(snapshot.getUniqueId(), snapshot);
            pendingSaveSignatures.put(snapshot.getUniqueId(), snapshotSignature);
        }
        return saved;
    }

    public void flushPendingSavesSync() {
        if (pendingSaveSnapshots.isEmpty()) {
            return;
        }

        int attempted = 0;
        int saved = 0;
        for (PlayerMusic snapshot : new ArrayList<>(pendingSaveSnapshots.values())) {
            Integer signature = pendingSaveSignatures.get(snapshot.getUniqueId());
            if (signature == null) {
                continue;
            }
            attempted++;
            if (persistSnapshotSync(snapshot.getUniqueId(), snapshot, signature)) {
                pendingSaveSnapshots.remove(snapshot.getUniqueId(), snapshot);
                pendingSaveSignatures.remove(snapshot.getUniqueId(), signature);
                saved++;
            }
        }

        if (saved > 0) {
            MusicBox.getInstance().getLogger().info("Flushed " + saved + " pending player music saves");
        }
        int remaining = pendingSaveSnapshots.size();
        if (remaining > 0) {
            MusicBox.getInstance().getLogger().warning(
                    "Still have " + remaining + " pending player music saves after flushing " + attempted + " snapshot(s)"
            );
        }
    }

    private boolean saveMusicSyncInternal(PlayerMusic music) {
        if (!DatabaseLoader.isInitialized()) {
            MusicBox.getInstance().getLogger().warning("Database is not initialized, cannot save player music");
            return false;
        }

        Connection connection = null;
        try {
            AbstractBase base = DatabaseLoader.getBase();
            connection = base.openConnection();
            connection.setAutoCommit(false);

            String upsertSql = base.getUpsertSql("player_music", 
                new String[]{"id", "name", "author", "author_uuid", "time_signature", "bpm", "beat_subdivision", "created_at", "updated_at", "description"}, 
                new String[]{"id"});
            base.executeUpdate(connection, upsertSql,
                music.getUniqueId().toString(),
                music.getName(),
                music.getAuthor(),
                music.getAuthorUUID().toString(),
                music.getTimeSignature().toString(),
                music.getBpm(),
                music.getBeatSubdivision(),
                music.getCreatedAt(),
                music.getUpdatedAt(),
                music.getDescription()
            );

            base.executeUpdate(connection, "DELETE FROM player_music_notes WHERE music_id = ?", music.getUniqueId().toString());

            if (!music.getNotes().isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                    base.resolveTableNames(
                        "INSERT INTO player_music_notes (music_id, pitch, tick, instruments) VALUES (?, ?, ?, ?)")
                )) {
                    String musicIdStr = music.getUniqueId().toString();
                    int batchSize = Math.max(1, MusicBox.getInstance().getConfigObject().getPerformance().getDatabaseBatchSize());
                    int count = 0;
                    for (MusicNote note : music.getNotes()) {
                        String instrumentsStr = note.getInstruments().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining(","));
                        ps.setString(1, musicIdStr);
                        ps.setInt(2, note.getPitch());
                        ps.setInt(3, note.getTick());
                        ps.setString(4, instrumentsStr);
                        ps.addBatch();
                        count++;
                        if (count % batchSize == 0) {
                            ps.executeBatch();
                            ps.clearBatch();
                        }
                    }
                    ps.executeBatch();
                }
            }

            connection.commit();
            return true;
        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to rollback music save transaction: " + rollbackEx.getMessage(), rollbackEx);
                }
            }
            MusicBox.getInstance().getLogger().log(Level.SEVERE, "Failed to save player music: " + music.getName(), e);
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    MusicBox.getInstance().getLogger().log(Level.WARNING, "Failed to close database connection", e);
                }
            }
        }
    }

    public boolean deleteMusic(UUID musicId) {
        return deleteMusicSync(musicId);
    }

    public CompletableFuture<Boolean> deleteMusicAsync(UUID musicId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!MusicBox.getInstance().isEnabled()) {
            future.complete(deleteMusicSync(musicId));
            return future;
        }
        AsyncTaskManager.runAsync(() -> future.complete(deleteMusicSync(musicId)), () -> future.complete(false));
        return future;
    }

    private boolean deleteMusicSync(UUID musicId) {
        PlayerMusic music = musicByIdCache.get(musicId);
        if (music == null) {
            return false;
        }

        if (!DatabaseLoader.isInitialized()) {
            return false;
        }

        UUID authorUUID = music.getAuthorUUID();

        synchronized (this) {
            try {
                AbstractBase base = DatabaseLoader.getBase();
                // Atomic transactional delete of the music row + its note rows (no reliance
                // on FK cascade / SQLite foreign_keys pragma).
                base.deletePlayerMusicWithNotes(musicId.toString());

                List<PlayerMusic> playerMusicList = playerMusicCache.get(authorUUID);
                if (playerMusicList != null) {
                    playerMusicList.removeIf(m -> m.getUniqueId().equals(musicId));
                }
                musicByIdCache.remove(musicId);
                com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
                pendingSaveSnapshots.remove(musicId);
                pendingSaveSignatures.remove(musicId);
                lastSavedSignatures.remove(musicId);
                saveChains.remove(musicId);
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(Level.SEVERE, "Failed to delete player music: " + musicId, e);
                return false;
            }
        }

        // Outside the lock (avoids nesting our monitor with the publish manager's): drop any
        // published listing that referenced this now-deleted source music so it can't dangle.
        com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager publishedManager =
                com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager.getExistingInstance();
        if (publishedManager != null) {
            publishedManager.onSourceMusicDeleted(authorUUID, musicId);
        }
        return true;
    }

    public CompletableFuture<Boolean> renameMusicAsync(UUID musicId, String newName) {
        if (!MusicBox.getInstance().isEnabled()) {
            return CompletableFuture.completedFuture(renameMusicSync(musicId, newName));
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(() -> future.complete(renameMusicSync(musicId, newName)), () -> future.complete(false));
        return future;
    }

    private boolean renameMusicSync(UUID musicId, String newName) {
        String validatedName = validateMusicName(newName);
        if (validatedName == null) {
            return false;
        }
        synchronized (this) {
            PlayerMusic music = musicByIdCache.get(musicId);
            if (music == null) {
                return false;
            }

            PlayerMusic existing = getMusicByName(music.getAuthorUUID(), validatedName);
            if (existing != null && !existing.getUniqueId().equals(musicId)) {
                return false;
            }

            String oldName = music.getName();
            music.setName(validatedName);

            if (saveMusicSync(music)) {
                return true;
            }

            music.setName(oldName);
            return false;
        }
    }

    public CompletableFuture<PlayerMusic> createMusicAsync(String name, String authorName, UUID authorUUID, int musicLimit) {
        String validatedName = validateMusicName(name);
        if (validatedName == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (!MusicBox.getInstance().isEnabled()) {
            return CompletableFuture.completedFuture(createMusicSync(validatedName, authorName, authorUUID, musicLimit));
        }

        CompletableFuture<PlayerMusic> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(
                () -> future.complete(createMusicSync(validatedName, authorName, authorUUID, musicLimit)),
                () -> future.complete(null)
        );
        return future;
    }

    private PlayerMusic createMusicSync(String validatedName, String authorName, UUID authorUUID, int musicLimit) {
        synchronized (this) {
            if (musicLimit != -1 && getMusicCount(authorUUID) >= musicLimit) {
                return null;
            }

            if (getMusicByName(authorUUID, validatedName) != null) {
                return null;
            }

            PlayerMusic music = new PlayerMusic(validatedName, authorName, authorUUID);
            if (!saveMusicSync(music)) {
                removeMusicFromCaches(authorUUID, music.getUniqueId());
                return null;
            }
            return music;
        }
    }

    private void removeMusicFromCaches(UUID authorUUID, UUID musicId) {
        List<PlayerMusic> playerMusicList = playerMusicCache.get(authorUUID);
        if (playerMusicList != null) {
            playerMusicList.removeIf(existing -> existing.getUniqueId().equals(musicId));
        }
        musicByIdCache.remove(musicId);
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
        pendingSaveSnapshots.remove(musicId);
        pendingSaveSignatures.remove(musicId);
        lastSavedSignatures.remove(musicId);
        saveChains.remove(musicId);
    }
    
    public String validateMusicName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = name.trim();
        
        if (trimmed.length() > 100) {
            trimmed = trimmed.substring(0, 100);
        }

        return trimmed.replaceAll("[<>\"'&\\x00-\\x1f\\x7f-\\x9f\\u00a7\\r\\n]", "");
    }

    public List<PlayerMusic> getMusicByPlayer(UUID playerUUID) {
        return playerMusicCache.getOrDefault(playerUUID, Collections.emptyList());
    }

    public List<PlayerMusic> getMusicByPlayer(Player player) {
        return getMusicByPlayer(player.getUniqueId());
    }

    public PlayerMusic getMusicById(UUID musicId) {
        return musicByIdCache.get(musicId);
    }

    public PlayerMusic getMusicByName(UUID playerUUID, String name) {
        List<PlayerMusic> musicList = playerMusicCache.get(playerUUID);
        if (musicList == null) {
            return null;
        }

        return musicList.stream()
                .filter(m -> m.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public int getMusicCount(UUID playerUUID) {
        List<PlayerMusic> musicList = playerMusicCache.get(playerUUID);
        return musicList != null ? musicList.size() : 0;
    }

    private static final String LIMIT_PERM_PREFIX = Permissions.EDIT_LIMIT_PREFIX;

    public int getMusicLimit(Player player) {
        if (player.hasPermission(Permissions.EDIT_LIMIT_UNLIMITED)) {
            return -1;
        }
        int best = -1;
        for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission();
            if (!perm.startsWith(LIMIT_PERM_PREFIX)) continue;
            String suffix = perm.substring(LIMIT_PERM_PREFIX.length());
            if (suffix.isEmpty() || suffix.indexOf('.') >= 0) continue;
            try {
                int n = Integer.parseInt(suffix);
                if (n > best) best = n;
            } catch (NumberFormatException ignored) {
            }
        }
        return best > 0 ? best : MusicBox.getInstance().getConfigObject().getEditor().getDefaultLimit();
    }

    public boolean canCreateMore(Player player) {
        int limit = getMusicLimit(player);
        if (limit == -1) {
            return false;
        }
        return getMusicCount(player.getUniqueId()) >= limit;
    }

    public void reload() {
        loadAllMusic();
    }
    
    public CompletableFuture<Void> reloadAsync() {
        if (!MusicBox.getInstance().isEnabled()) {
            flushPendingSavesSync();
            loadAllMusic();
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(() -> {
            try {
                flushPendingSavesSync();
                loadAllMusic();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, () -> future.completeExceptionally(new IllegalStateException("Player music reload cancelled")));
        return future;
    }

    public void shutdown() {
        flushPendingSavesSync();
        playerMusicCache.clear();
        musicByIdCache.clear();
        pendingSaveSnapshots.clear();
        pendingSaveSignatures.clear();
        lastSavedSignatures.clear();
        saveChains.clear();
        synchronized (LOCK) {
            if (instance == this) {
                instance = null;
            }
        }
    }
}

