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
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PlayerMusicManager {

    private static volatile PlayerMusicManager instance;
    private static final Object LOCK = new Object();
    private final Map<UUID, List<PlayerMusic>> playerMusicCache = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerMusic> musicByIdCache = new ConcurrentHashMap<>();
    // What still has to reach the database. Each song's saves are ordered by a version so a failed
    // write can never put an older snapshot back over one the player edited in the meantime.
    private final PendingSaves pendingSaves = new PendingSaves();
    // SHA-256 strings, not 32-bit object hashes: equality here can skip a durable write.
    private final Map<UUID, String> lastSavedDigests = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastWrittenNoteDigests = new ConcurrentHashMap<>();
    // Compared under this manager's monitor. Old asynchronous completions must not write behind a
    // newer synchronous save (or after a pending queue has drained and been refilled).
    private final Map<UUID, Long> lastSavedVersions = new ConcurrentHashMap<>();
    private final java.util.function.Predicate<PlayerMusic> persistenceOverride;
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
        this.persistenceOverride = null;
    }

    // Inject only the durable-write boundary for deterministic ordering/rollback tests, without
    // starting Bukkit or a JDBC pool. Public entry points still use the same coordinator below.
    PlayerMusicManager(java.util.function.Predicate<PlayerMusic> persistenceOverride) {
        this.persistenceOverride = Objects.requireNonNull(persistenceOverride);
    }

    private void cacheMusic(PlayerMusic music) {
        if (music == null) {
            return;
        }

        UUID musicId = music.getUniqueId();
        UUID authorUUID = music.getAuthorUUID();
        PlayerMusic previous = musicByIdCache.put(musicId, music);
        // Rebuild any disc adapter so edits/renames are reflected on player-music discs.
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
        if (previous == music) {
            // Normal editor saves keep the same object. Removing and re-adding it to a
            // CopyOnWriteArrayList copied the entire owner's catalogue twice per save (and once
            // more through saveMusic's redundant call), despite no membership change.
            return;
        }

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

            // Notes are grouped straight into MusicNote while the ResultSet streams. Collecting the
            // rows first meant holding one ResultSetRow + builder map per note of the whole server
            // at once (~300 B/note, ~150 MB at 500k notes) only to copy each into a MusicNote a
            // moment later. Grouping as rows arrive also keeps the same per-song order the old
            // `notesMap` had, which was populated in query order too, so no ORDER BY is needed.
            Map<String, List<MusicNote>> notesMap = new HashMap<>();
            base.executeStreamQuery(
                "SELECT music_id, pitch, tick, instruments FROM player_music_notes",
                noteRow -> {
                    String noteMusicId = noteRow.getString("music_id");
                    MusicNote note = this.parseNoteRow(noteRow);
                    if (noteMusicId == null || note == null) {
                        return;
                    }
                    notesMap.computeIfAbsent(noteMusicId, k -> new ArrayList<>()).add(note);
                }
            );

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

                    // remove() hands the list over to PlayerMusic (whose constructor copies it) and
                    // drops the map entry, so nothing is aliased or held twice.
                    List<MusicNote> notes = notesMap.remove(idStr);
                    if (notes == null) {
                        notes = new ArrayList<>();
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
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.clearPlayerMusicAdapters();

        MusicBox.getInstance().getLogger().info(com.huidu.musicboxplus.common.utils.LogLocale.text(MusicBox.getInstance(), "Loaded " + musicByIdCache.size() + " player music entries", "已加载 " + musicByIdCache.size() + " 条玩家音乐记录"));
    }

    private MusicNote.NoteInstrument parseInstrument(String name) {
        return MusicNote.NoteInstrument.parseStored(name);
    }

    /**
     * Builds one note from a `player_music_notes` row, or null when the row cannot describe a note.
     *
     * A malformed row is skipped rather than throwing: the read now happens inside a single
     * streaming query, so an NPE here would abort the whole reload and leave the previous cache in
     * place. Before, the note loop ran inside the per-song try/catch, so one bad row only cost the
     * owning song -- skipping the row is strictly friendlier than either.
     */
    MusicNote parseNoteRow(ResultSetRow row) {
        Integer pitch = row.getInt("pitch");
        Integer tick = row.getInt("tick");
        if (pitch == null || tick == null) {
            return null;
        }
        String instrumentsStr = row.getString("instruments");
        List<MusicNote.NoteInstrument> instruments = instrumentsStr == null
            ? Collections.emptyList()
            : Arrays.stream(instrumentsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseInstrument)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new MusicNote(pitch, tick, instruments);
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

        stageSnapshot(music);
    }

    public CompletableFuture<Boolean> saveMusicAsync(PlayerMusic music) {
        cacheMusic(music);
        PendingSaves.Save staged = stageSnapshot(music);
        UUID musicId = staged.snapshot().getUniqueId();
        // Do not decide a signature hit on the caller's thread: a synchronous/in-flight write may
        // still change the durable row. Every path rechecks it under the persistence monitor.
        if (!MusicBox.getInstance().isEnabled()) {
            return CompletableFuture.completedFuture(persistSaveSync(staged));
        }
        // Publishing the successor atomically avoids two callers branching off the same predecessor.
        // Attach cleanup outside compute: a completed predecessor may run thenCompose inline.
        CompletableFuture<Boolean> next = saveChains.compute(musicId, (id, previous) -> {
            CompletableFuture<Boolean> predecessor = previous == null
                    ? CompletableFuture.completedFuture(true) : previous;
            return predecessor.handle((ignored, throwable) -> null)
                    .thenCompose(ignored -> persistLatestSaveAsync(id));
        });
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

    private CompletableFuture<Boolean> persistLatestSaveAsync(UUID musicId) {
        PendingSaves.Save save = pendingSaves.peek(musicId);
        if (save == null) {
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AsyncTaskManager.runAsync(() -> {
            try {
                future.complete(persistSaveSync(save));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, () -> future.complete(false));
        return future;
    }

    boolean persistSaveSync(PendingSaves.Save save) {
        UUID musicId = save.snapshot().getUniqueId();
        // Keep ordering with explicit delete. Coalesce to the current pending entry before writing:
        // an executor callback may have captured an older entry before a synchronous save completed.
        synchronized (this) {
            while (true) {
                if (musicByIdCache.get(musicId) == null) {
                    pendingSaves.retire(musicId, save.version());
                    return false;
                }
                PendingSaves.Save latest = pendingSaves.peek(musicId);
                Long durableVersion = lastSavedVersions.get(musicId);
                if (latest == null) {
                    // This token may have been explicitly withdrawn by a failed Web update.
                    // Only an already-durable completion can succeed without a pending entry.
                    return durableVersion != null && save.version() <= durableVersion;
                }
                // The currently staged entry is authoritative even when an aborted Web request
                // restored an earlier editor retry. Never resurrect a withdrawn captured request.
                save = latest;
                if (durableVersion != null && save.version() <= durableVersion) {
                    pendingSaves.retire(musicId, save.version());
                } else if (Objects.equals(lastSavedDigests.get(musicId), save.fingerprint().song())) {
                    lastSavedVersions.put(musicId, save.version());
                    pendingSaves.retire(musicId, save.version());
                } else {
                    if (!saveMusicSyncInternal(save.snapshot(), save.fingerprint().notes())) {
                        // Entry remains staged on failure. Never put an old snapshot over a new one.
                        return false;
                    }
                    lastSavedDigests.put(musicId, save.fingerprint().song());
                    lastSavedVersions.put(musicId, save.version());
                    pendingSaves.retire(musicId, save.version());
                }
                latest = pendingSaves.peek(musicId);
                if (latest == null || latest.version() <= save.version()) {
                    return true;
                }
                // Iterative rather than recursive: continuous edits must not grow the Java stack.
                save = latest;
            }
        }
    }

    // Model monitor covers capture, fingerprint and publication. A slow old snapshot can no longer
    // be assigned a newer queue token after another caller captured and committed newer content.
    PendingSaves.Save stageSnapshot(PlayerMusic music) {
        synchronized (music) {
            PlayerMusic snapshot = music.snapshot();
            return pendingSaves.stage(snapshot.getUniqueId(), snapshot);
        }
    }

    // Test access to the same coordinator the public save methods use.
    PendingSaves.Save stageSaveForTest(PlayerMusic music) {
        musicByIdCache.put(music.getUniqueId(), music);
        return stageSnapshot(music);
    }

    PendingSaves.Save pendingSaveForTest(UUID id) {
        return pendingSaves.peek(id);
    }

    /**
     * True when this instance has no record of committing exactly these notes.
     *
     * <p>The comparison is a SHA-256 content digest, not {@code Objects.hash}/{@code hashCode}. A
     * 32-bit fold of (pitch, tick, instruments) collides easily enough to be reachable by ordinary
     * edits -- pitch 45/tick 0 and pitch 44/tick 31 fold to the same int -- and a false match drops a
     * real save, which is exactly the failure direction that must never happen here.
     *
     * <p>The record is per process: after a restart the first save of each song rewrites its notes in
     * full. On a database shared by several servers, a metadata-only save leaves whatever another
     * server wrote in the note table alone.
     */
    boolean needsNoteRewrite(UUID musicId, String notesDigest) {
        return !Objects.equals(lastWrittenNoteDigests.get(musicId), notesDigest);
    }

    void markNotesWritten(UUID musicId, String notesDigest) {
        lastWrittenNoteDigests.put(musicId, notesDigest);
    }

    public boolean saveMusicSync(PlayerMusic music) {
        cacheMusic(music);

        PendingSaves.Save staged = stageSnapshot(music);
        // A failure leaves the snapshot staged (persistSaveSync puts it back when nothing newer
        // arrived), so both the shutdown flush and the next save still have a chance to persist it.
        return persistSaveSync(staged);
    }

    public void flushPendingSavesSync() {
        if (pendingSaves.isEmpty()) {
            return;
        }

        int attempted = 0;
        int saved = 0;
        for (PendingSaves.Save save : pendingSaves.staged()) {
            attempted++;
            if (persistSaveSync(save)) {
                saved++;
            }
        }

        if (saved > 0) {
            MusicBox.getInstance().getLogger().info("Flushed " + saved + " pending player music saves");
        }
        int remaining = pendingSaves.size();
        if (remaining > 0) {
            MusicBox.getInstance().getLogger().warning(
                    "Still have " + remaining + " pending player music saves after flushing " + attempted + " snapshot(s)"
            );
        }
    }

    private boolean saveMusicSyncInternal(PlayerMusic music, String notesDigest) {
        if (persistenceOverride != null) {
            return persistenceOverride.test(music);
        }
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

            // The note table is only rewritten when the notes actually changed. It used to be
            // DELETE-then-re-INSERT on every save, so renaming a 20k-note song re-inserted all 20k
            // rows to change one string in player_music. The fingerprint is a per-process record of
            // what this instance last committed, so a first save after startup still writes in full.
            boolean notesRewritten = needsNoteRewrite(music.getUniqueId(), notesDigest);
            if (notesRewritten) {
                base.executeUpdate(connection, "DELETE FROM player_music_notes WHERE music_id = ?", music.getUniqueId().toString());

                if (music.getNoteCount() > 0) {
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
            }

            connection.commit();
            if (notesRewritten) {
                // Only after the commit: a rolled-back transaction must not claim the notes are in.
                markNotesWritten(music.getUniqueId(), notesDigest);
            }
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

    /**
     * Forgets a song that was never handed to the player.
     *
     * <p>Used by the purchase flow: the copy is created and cached before its write is attempted, so
     * a failed or refunded purchase has to withdraw it from the in-memory library as well. {@link
     * #deleteMusicAsync} cannot do that job, because it returns early when the database is not
     * initialised -- which is exactly the failure that leaves the copy behind. A copy that stays
     * cached is visible in the buyer's library, editable, and is flushed to the database by the
     * shutdown save of the pending queue, i.e. a refunded purchase would become a free song.
     *
     * <p>This deliberately does not touch the database: if a row was written by an earlier attempt,
     * the caller is expected to also request a delete.
     */
    public void discardUnsavedMusic(UUID musicId) {
        if (musicId == null) {
            return;
        }
        synchronized (this) {
            PlayerMusic music = musicByIdCache.get(musicId);
            if (music != null) {
                removeMusicFromCaches(music.getAuthorUUID(), musicId);
                return;
            }
            // No cached instance to read the author from: clear every per-song record directly and
            // sweep the author buckets, which are small lists.
            pendingSaves.drop(musicId);
            lastSavedDigests.remove(musicId);
            lastSavedVersions.remove(musicId);
            lastWrittenNoteDigests.remove(musicId);
            saveChains.remove(musicId);
            for (List<PlayerMusic> bucket : playerMusicCache.values()) {
                bucket.removeIf(existing -> existing.getUniqueId().equals(musicId));
            }
            com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
        }
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
                pendingSaves.drop(musicId);
                lastSavedDigests.remove(musicId);
                lastSavedVersions.remove(musicId);
                lastWrittenNoteDigests.remove(musicId);
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

    /**
     * Applies {@code mutation} to the live song and persists the result, all under the manager
     * monitor.
     *
     * <p>This is the write path for callers outside the in-game editor (the web editor and its
     * import endpoint). Routing them through here instead of {@code synchronized (music)} around
     * {@link #saveMusicSync} fixes two things at once:
     *
     * <ul>
     *   <li><b>Lock order.</b> Rename, delete and create take the manager monitor and then the
     *       song's; the web write path took them the other way round, which is an ABBA deadlock
     *       waiting for two concurrent requests (a PUT and a rename).
     *   <li><b>Lost updates and rollback.</b> The mutation sees the song as it is when the lock was
     *       acquired, and a failed save restores the in-memory object exactly, so memory and
     *       database cannot disagree. Previously each caller snapshotted before the lock and rolled
     *       back with its own copy, which could undo somebody else's successful write.
     * </ul>
     *
     * <p>The mutation may throw to reject the request; it only ever touches the working copy, so the
     * live song is untouched when that happens.
     *
     * @return true when the change was committed; false when the song is gone or the write failed
     */
    public boolean updateMusicSync(UUID musicId, UnaryOperator<PlayerMusic> mutation) {
        if (musicId == null || mutation == null) {
            return false;
        }
        synchronized (this) {
            PlayerMusic live = musicByIdCache.get(musicId);
            if (live == null) {
                return false;
            }
            // Keep the model locked in the established manager -> model order. An editor can be
            // opened after the HTTP precheck; its model mutations must not interleave between the
            // working-copy capture and successful publication. stageSnapshot never takes manager.
            synchronized (live) {
                PlayerMusic replacement = mutation.apply(live.snapshot());
                if (replacement == null) {
                    return false;
                }
                if (!musicId.equals(replacement.getUniqueId())
                        || !live.getAuthorUUID().equals(replacement.getAuthorUUID())) {
                    throw new IllegalArgumentException("An update cannot change the music identity or owner");
                }
                // Commit the detached working copy before publishing it. A rejected Web request must
                // not leave its failed content in pendingSaves to be silently committed at shutdown.
                PendingSaves.Save previousPending = pendingSaves.peek(musicId);
                PendingSaves.Save request = pendingSaves.stage(musicId, replacement);
                boolean committed = false;
                try {
                    committed = saveMusicSyncInternal(replacement, request.fingerprint().notes());
                    if (!committed) {
                        return false;
                    }
                    lastSavedDigests.put(musicId, request.fingerprint().song());
                    lastSavedVersions.put(musicId, request.version());
                    live.applySnapshot(replacement);
                    com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
                    return true;
                } finally {
                    if (pendingSaves.retire(musicId, request.version()) && !committed && previousPending != null) {
                        // Restore a pre-existing editor retry, not the rejected request. CAS prevents
                        // replacing an edit staged while this request was doing I/O.
                        pendingSaves.restoreIfEmpty(musicId, previousPending);
                    }
                }
            }
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

    /**
     * Removes a song that was cached but never written, without touching the database.
     *
     * <p>{@link #saveMusicSync} caches before it writes, so a failed write left the song visible in
     * the player's library with no row behind it. Callers that create a song and then fail to persist
     * it use this instead of {@link #deleteMusic} (which would also run the published-listing
     * cleanup for a song that never existed there).
     */
    public void discardUnsavedMusic(PlayerMusic music) {
        if (music == null) {
            return;
        }
        removeMusicFromCaches(music.getAuthorUUID(), music.getUniqueId());
    }

    private void removeMusicFromCaches(UUID authorUUID, UUID musicId) {
        List<PlayerMusic> playerMusicList = playerMusicCache.get(authorUUID);
        if (playerMusicList != null) {
            playerMusicList.removeIf(existing -> existing.getUniqueId().equals(musicId));
        }
        musicByIdCache.remove(musicId);
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.invalidatePlayerMusicAdapter(musicId);
        pendingSaves.drop(musicId);
        lastSavedDigests.remove(musicId);
        lastSavedVersions.remove(musicId);
        lastWrittenNoteDigests.remove(musicId);
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

    /**
     * Number of player-created songs held in memory, for diagnostics. This is the loaded set, not a
     * database count: a fresh start that failed to load shows 0 even when rows exist, which is
     * exactly the distinction {@code /musicboxplus doctor} needs to expose.
     */
    public int getLoadedMusicCount() {
        return musicByIdCache.size();
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

    public boolean isMusicLimitReached(Player player) {
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
        com.huidu.musicboxplus.core.song.MusicBoxSongManager.clearPlayerMusicAdapters();
        pendingSaves.clear();
        lastSavedDigests.clear();
        lastSavedVersions.clear();
        lastWrittenNoteDigests.clear();
        saveChains.clear();
        synchronized (LOCK) {
            if (instance == this) {
                instance = null;
            }
        }
    }
}
