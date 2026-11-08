package com.huidu.musicboxplus.core.db;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.core.db.utils.NamedParamStatement;
import com.huidu.musicboxplus.core.db.utils.ResultSetRow;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// Shared database operations for both backends; SQLite and MySQL differ only where a
// subclass or isMySQL() says so.
public abstract class AbstractBase {
    private final String name;
    private boolean initialized = false;

    protected AbstractBase(String name) {
        this.name = name;
    }

    public abstract boolean isMySQL();

    protected String getTablePrefix() {
        return "";
    }

    private static final String[] TABLE_NAMES = {
        "block_player_volumes", "player_music_notes", "player_music",
        "playlist_song", "recent_songs", "player_volumes",
        "sign_songs", "playlists", "signs",
        // Bookkeeping for SchemaMigrator, not plugin data; listed so table prefixes reach it too.
        com.huidu.musicboxplus.core.db.schema.SchemaMigrator.VERSION_TABLE
    };

    // Memoised: prepare() resolves every statement, and the SQL strings are a small fixed set,
    // but each resolution ran nine String.replaceAll -- nine Pattern.compile plus nine full scans.
    private final Map<String, String> resolvedSql = new ConcurrentHashMap<>();

    // Public: PlayerMusicManager builds its own batch statement and needs the same resolution.
    public String resolveTableNames(String sql) {
        String prefix = getTablePrefix();
        if (prefix.isEmpty()) {
            return sql;
        }
        return resolvedSql.computeIfAbsent(sql, this::applyTablePrefix);
    }

    private String applyTablePrefix(String sql) {
        String prefix = getTablePrefix();
        for (String table : TABLE_NAMES) {
            // Word boundaries stop "player_music" from also matching inside the longer
            // "player_music_notes", and from re-matching its own already-prefixed form -- a
            // plain String.replace double-prefixes both. "_" is a word char, so there is no
            // \b between "player_music" and "_notes".
            sql = sql.replaceAll("\\b" + table + "\\b", java.util.regex.Matcher.quoteReplacement(prefix + table));
        }
        return sql;
    }

    private static final Set<String> ALLOWED_TABLES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "playlists", "playlist_song", "signs", "sign_songs",
            "recent_songs", "player_volumes", "block_player_volumes", "player_music", "player_music_notes"
        ))
    );

    private static final Set<String> ALLOWED_COLUMNS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "id", "owner", "name", "playlists_id", "song_hash", "pos",
            "location", "world", "x", "y", "z", "radius", "options",
            "player_uuid", "hash", "played_at", "volume",
            "author", "author_uuid", "time_signature", "bpm", "beat_subdivision",
            "created_at", "updated_at", "description",
            "music_id", "pitch", "tick", "instruments"
        ))
    );

    private void validateTableName(String table) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException("无效的表名: " + table);
        }
    }

    private void validateColumnName(String column) {
        if (!ALLOWED_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("无效的列名: " + column);
        }
    }

    public String getUpsertSql(String table, String[] columns, String[] primaryKeyColumns) {
        // Validate against the allowlist on the bare (un-prefixed) names, THEN apply the
        // table prefix -- the allowlist holds bare names, so validating after prefixing
        // rejects every table whenever a prefix is configured.
        validateTableName(table);
        for (String col : columns) {
            validateColumnName(col);
        }
        for (String pk : primaryKeyColumns) {
            validateColumnName(pk);
        }
        table = resolveTableNames(table);

        // Everything except the key columns; an upsert must never rewrite a key.
        List<String> updates = new ArrayList<>();
        for (String col : columns) {
            if (!isPrimaryKey(col, primaryKeyColumns)) {
                updates.add(col);
            }
        }
        String placeholders = String.join(", ", Collections.nCopies(columns.length, "?"));

        if (isMySQL()) {
            StringBuilder sql = new StringBuilder("INSERT INTO ");
            sql.append(table).append(" (");
            sql.append(String.join(", ", columns));
            sql.append(") VALUES (").append(placeholders).append(')');
            sql.append(" ON DUPLICATE KEY UPDATE ");
            if (updates.isEmpty()) {
                // Key columns only: the statement is already idempotent, so the duplicate branch
                // has to be a no-op instead of an empty (and therefore invalid) update list.
                String pk = primaryKeyColumns[0];
                sql.append(pk).append(" = ").append(pk);
            } else {
                sql.append(updates.stream()
                        .map(col -> col + " = VALUES(" + col + ")")
                        .collect(Collectors.joining(", ")));
            }
            return sql.toString();
        }

        // SQLite must not use INSERT OR REPLACE here. REPLACE does not update in place: it deletes
        // the conflicting row and inserts a new one, which fires ON DELETE CASCADE on every child
        // table. player_music_notes.music_id cascades from player_music and the connection enables
        // foreign_keys, so "INSERT OR REPLACE INTO player_music" deleted the whole song's notes on
        // every metadata-only save -- and the note table is deliberately only rewritten when its
        // fingerprint changes, so nothing put them back. ON CONFLICT ... DO UPDATE updates in place
        // and leaves the children alone (SQLite 3.24+, far below the driver Paper ships).
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(table).append(" (");
        sql.append(String.join(", ", columns));
        sql.append(") VALUES (").append(placeholders).append(')');
        sql.append(" ON CONFLICT (").append(String.join(", ", primaryKeyColumns)).append(')');
        if (updates.isEmpty()) {
            return sql.append(" DO NOTHING").toString();
        }
        sql.append(" DO UPDATE SET ");
        sql.append(updates.stream()
                .map(col -> col + " = excluded." + col)
                .collect(Collectors.joining(", ")));
        return sql.toString();
    }

    private static boolean isPrimaryKey(String column, String[] primaryKeyColumns) {
        for (String pk : primaryKeyColumns) {
            if (pk.equals(column)) {
                return true;
            }
        }
        return false;
    }

    // Deletes a player_music row together with its player_music_notes rows in a single
    // transaction. Avoids relying on the FK ON DELETE CASCADE (which needs SQLite's
    // foreign_keys pragma to be on) and guarantees we never leave a music row whose notes
    // were only half-removed.
    public void deletePlayerMusicWithNotes(String musicId) throws SQLException {
        try (Connection connection = this.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement notes = this.prepare(connection, "DELETE FROM player_music_notes WHERE music_id = ?", musicId);
                 PreparedStatement parent = this.prepare(connection, "DELETE FROM player_music WHERE id = ?", musicId)) {
                notes.executeUpdate();
                parent.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    protected int getLastInsertId(Connection connection) throws SQLException {
        String sql = isMySQL() ? "SELECT LAST_INSERT_ID()" : "SELECT last_insert_rowid()";
        try (PreparedStatement stmt = connection.prepareStatement(resolveTableNames(sql));
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new SQLException("Failed to get last insert ID");
        }
    }

    // Returns the auto-commit flag of a pooled connection after a manual transaction. Hikari
    // resets the flag when the connection is returned, but restoring it explicitly keeps the
    // behaviour correct for any pool that does not.
    private static void restoreAutoCommit(Connection connection, boolean previousAutoCommit) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException ignored) {
            // The caller's try-with-resources closes the connection anyway.
        }
    }

    // Both SQLite and MySQL report a missing table with different wording ("no such table" vs
    // "doesn't exist"); recognising both keeps the recreate-and-retry recovery path working on
    // MySQL, where matching only the SQLite wording left it silently dead.
    private static boolean isMissingTableError(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage();
        return message.contains("no such table") || message.contains("doesn't exist");
    }

    public void shutdown() {
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    protected static List<String> getColumns(ResultSet set) throws SQLException {
        ResultSetMetaData meta = set.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnName(i));
        }
        return columns;
    }

    /**
     * Pumps every row of an open ResultSet into {@code consumer}, building one ResultSetRow at a
     * time. See {@link #streamQuery} for why the streaming form exists; this is its testable core.
     */
    static void forEachRow(ResultSet set, RowConsumer consumer) throws SQLException {
        List<String> columns = getColumns(set);
        while (set.next()) {
            ResultSetRow.ResultSetRowBuilder row = ResultSetRow.builder();
            for (String column : columns) {
                row.addResultRow(column, set.getObject(column));
            }
            consumer.accept(row.build());
        }
    }

    protected static List<ResultSetRow> extractSet(ResultSet set) throws SQLException {
        List<ResultSetRow> rows = new ArrayList<>();
        forEachRow(set, rows::add);
        return Collections.unmodifiableList(rows);
    }

    // Statements of a creation script. Line comments go first: splitting the raw script on ';'
    // cuts a comment containing one in half and feeds the tail to the driver as SQL, which is
    // how a comment mentioning "MySQL.sql" reached SQLite as `near "MySQL": syntax error`.
    //
    // ponytail: strips -- anywhere on a line, including inside a string literal. The two shipped
    // scripts have no such literal; use a real parser if that ever changes.
    static List<String> splitScript(String script) {
        List<String> statements = new ArrayList<>();
        for (String part : script.replaceAll("(?m)--.*$", "").split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    protected void afterInit() {
        try (Connection connection = this.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : splitScript(this.getCreationScript())) {
                try {
                    statement.executeUpdate(sql);
                } catch (SQLException e) {
                    // MySQL's CREATE INDEX has no IF NOT EXISTS, so re-initializing an
                    // existing database throws a duplicate-index error. That is expected
                    // and harmless (indexes are performance-only); skip it instead of
                    // aborting plugin startup. Any other DDL failure is still fatal.
                    if (sql.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE INDEX")) {
                        MusicBox.getInstance().getLogger().fine("Skipping existing index: " + e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
            // The creation script only ever creates what is missing; it cannot change an existing
            // table. Everything after the baseline shape goes through the versioned migrations, so a
            // database from any earlier release is brought forward instead of being silently used
            // with the wrong layout.
            com.huidu.musicboxplus.core.db.schema.SchemaMigrator.Result migration =
                    com.huidu.musicboxplus.core.db.schema.SchemaMigrator.migrate(
                            connection, this::resolveTableNames, this.isMySQL(), 0);
            if (migration.adoptedBaseline()) {
                MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(),
                        "Database schema version recorded: " + migration.finalVersion(),
                        "已记录数据库结构版本: " + migration.finalVersion()));
            }
            if (!migration.applied().isEmpty()) {
                for (com.huidu.musicboxplus.core.db.schema.Migration applied : migration.applied()) {
                    MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(),
                            "Applied database migration v" + applied.version() + ": " + applied.description(),
                            "已应用数据库迁移 v" + applied.version() + ": " + applied.description()));
                }
            }
            this.initialized = true;
            MusicBox.getInstance().getLogger().info(LogLocale.text(MusicBox.getInstance(), "Database tables initialized", "数据库表初始化完成"));
        } catch (SQLException e) {
            MusicBox.getInstance().getLogger().severe(LogLocale.text(MusicBox.getInstance(), "Database initialization failed: " + e.getMessage(), "数据库初始化失败: " + e.getMessage()));
            throw new RuntimeException("数据库初始化失败", e);
        }
    }


    protected void ensureTablesExist() {
        try (Connection connection = this.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : splitScript(this.getCreationScript())) {
                try {
                    statement.executeUpdate(sql);
                } catch (SQLException e) {
                    // Same idempotency handling as afterInit(): MySQL's CREATE INDEX has no
                    // IF NOT EXISTS, so on an existing DB it throws a duplicate-index error.
                    // Skip it so the index failure can't abort the rest of the recovery
                    // script and leave a genuinely-missing table uncreated.
                    if (sql.toUpperCase(java.util.Locale.ROOT).startsWith("CREATE INDEX")) {
                        MusicBox.getInstance().getLogger().fine("Skipping existing index: " + e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
        } catch (SQLException e) {
            MusicBox.getInstance().getLogger().warning("确保表存在时出错: " + e.getMessage());
        }
    }

    @Language(value = "SQL")
    protected String getCreationScript() {
        try {
            String fileName = this.name + ".sql";
            String path = "db/" + fileName;
            try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream(path)) {
                if (stream == null) {
                    throw new IOException("Database script not found: " + path);
                }
                return resolveTableNames(StringUtils.getString(stream));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Flushes SQLite's write-ahead log into the main database file so a plain file copy is a
     * complete snapshot.
     *
     * <p>A copy of {@code data.db} without this can miss every write still sitting in
     * {@code data.db-wal}; restoring such a file silently loses recent edits. MySQL has no
     * equivalent file to copy (its backup needs a dump), so this is a no-op there.
     *
     * @return true when the checkpoint succeeded, false when the database is not SQLite or the
     *         checkpoint failed. Callers must not claim a consistent database copy on false.
     */
    public boolean checkpointForBackup() {
        if (isMySQL()) {
            return false;
        }
        try (Connection connection = this.acquireConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            return true;
        } catch (SQLException e) {
            MusicBox.getInstance().getLogger().warning(
                    "SQLite WAL checkpoint before backup failed: " + e.getMessage());
            return false;
        }
    }

    public static <T> void setValue(PreparedStatement statement, int i, T obj) throws SQLException {
        int index = i + 1;
        statement.setObject(index, obj);
    }

    // Acquire a raw pooled connection. Implemented per backend (SQLite/MySQL).
    protected abstract Connection acquireConnection() throws SQLException;

    // Single guarded chokepoint for connection acquisition. EVERY read/write path routes through
    // here -- including the write helpers that open their own connection (saveSignSong, deleteMe,
    // savePlayerVolume, saveBlockPlayerVolume, ...) and the transaction blocks -- so the
    // main-thread / Folia-region DB guard fires for all of them, not just the query()/update()
    // entry points. The connection-taking query()/update() overloads reuse this already-guarded
    // connection, so they don't re-check.
    protected final Connection getConnection() throws SQLException {
        warnIfMainThread("getConnection");
        return acquireConnection();
    }

    protected PreparedStatement prepare(Connection connection, String query, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(resolveTableNames(query));
        for (int i = 0; i < args.length; i++) {
            AbstractBase.setValue(statement, i, args[i]);
        }
        return statement;
    }

    protected List<ResultSetRow> query(Connection connection, @Language(value = "SQL") String query, Object... args) throws SQLException {
        try (PreparedStatement prepared = this.prepare(connection, query, args)) {
            return this.extractSet(prepared.executeQuery());
        }
    }

    protected List<ResultSetRow> query(@Language(value = "SQL") String query, Object... args) {
        try (Connection connection = this.getConnection()) {
            return this.query(connection, query, args);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Receives one row at a time from {@link #streamQuery}. */
    @FunctionalInterface
    public interface RowConsumer {
        void accept(ResultSetRow row);
    }

    /**
     * Runs a query and hands each row to {@code consumer} while the driver's ResultSet is still
     * open, instead of collecting every row into a List first.
     *
     * Use this for tables that hold one row per note/event rather than one row per record.
     * {@link #extractSet} keeps every ResultSetRow alive until the query returns, and a row costs a
     * ResultSetRow, an unmodifiable LinkedHashMap and a builder's two ArrayLists -- roughly 300 B
     * for a four-column table. `player_music_notes` is 4 columns and holds every note of every
     * player song, so a library with 500k notes needed ~150 MB of heap just to be read, before the
     * notes were copied a second time into MusicNote objects. Here each row becomes garbage as soon
     * as the consumer returns.
     *
     * The consumer runs on the calling thread and must not retain the row: the backing map is not
     * reused, but keeping a reference defeats the point of streaming. An exception from the
     * consumer propagates to the caller and aborts the query, matching query()'s failure mode.
     */
    protected void streamQuery(@Language(value = "SQL") String query, RowConsumer consumer, Object... args) {
        try (Connection connection = this.getConnection();
             PreparedStatement prepared = this.prepare(connection, query, args);
             ResultSet set = prepared.executeQuery()) {
            forEachRow(set, consumer);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void warnIfMainThread(String operation) {
        MusicBox plugin = MusicBox.getInstance();
        // Suppressed during shutdown: onDisable runs its database cleanup synchronously on the
        // main thread by design.
        if (plugin != null && plugin.isShuttingDown()) {
            return;
        }
        // isPrimaryThread() is CraftServer.isPrimaryThread() -> TickThread.isTickThread(), so on
        // Folia this fires for a *region* thread too, not just the global one. That is the right
        // thing to warn about there as well -- a blocking query stalls every player that region
        // owns, which is worse than stalling one main thread -- but the wording below says "main
        // thread" because that is what it is on Paper.
        if (Bukkit.isPrimaryThread()) {
            String message = "[数据库警告] 操作 '" + operation + "' 在主线程执行，这可能导致服务器卡顿！请使用异步方法。";
            MusicBox.getInstance().getLogger().warning(message);

            MusicBoxConfig config = MusicBox.getInstance().getConfigObject();
            if (config != null) {
                if (config.isDebug()) {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    String trace = java.util.Arrays.stream(stackTrace)
                        .limit(6)
                        .map(StackTraceElement::toString)
                        .collect(java.util.stream.Collectors.joining("\n  "));
                    MusicBox.getInstance().getLogger().warning("调用堆栈:\n  " + trace);
                }
                if (config.isBlockMainThreadDb()) {
                    throw new IllegalStateException("[数据库错误] 禁止在主线程执行数据库操作: " + operation + "。请在配置中设置 blockMainThreadDb: false 以允许此操作（不推荐）");
                }
            }
        }
    }

    protected int update(NamedParamStatement statement) {
        try (Connection connection = this.getConnection()) {
            return statement.executeUpdate(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected int update(@Language(value = "SQL") String query, Object... args) {
        try (Connection connection = this.getConnection()) {
            return this.update(connection, query, args);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected int update(Connection connection, @Language(value = "SQL") String query, Object... args) throws SQLException {
        try (PreparedStatement prepared = this.prepare(connection, query, args)) {
            return prepared.executeUpdate();
        }
    }

    private void largeQuery() {
        if (Bukkit.isPrimaryThread()) {
            MusicBoxConfig config = MusicBox.getInstance().getConfigObject();
            if (config != null && config.isBlockMainThreadDb()) {
                throw new IllegalStateException("数据库操作不允许在主线程执行！请使用异步任务。");
            }
            MusicBox.getInstance().getLogger().warning(
                "[数据库警告] 大量数据库操作在主线程执行，这可能导致服务器卡顿！");
        }
    }

    public void savePlayList(PlayerPlayListModel list) {
        this.largeQuery();
        try (Connection connection = this.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean createNew = list.getId() == -1;
                String query = createNew
                    ? "INSERT INTO playlists (owner,name) values (:owner,:name)"
                    : "UPDATE playlists set name = :name where id = :id";
                NamedParamStatement statement = new NamedParamStatement(resolveTableNames(query));
                statement.setValue("id", list.getId());
                statement.setValue("owner", list.getOwner().toString());
                statement.setValue("name", list.getName());
                statement.executeUpdate(connection);

                if (createNew) {
                    int result = getLastInsertId(connection);
                    list.setId(result);
                } else {
                    this.update(connection, "DELETE from playlist_song where playlists_id = ?", list.getId());
                }

                // Unresolvable rows are carried through rather than dropped: they were never in
                // getSongs(), so rewriting from getSongs() alone deleted them for good. See
                // playlistRowHashes.
                List<Integer> resolvedHashes = new ArrayList<>(list.getSongs().size());
                for (MusicBoxSong song : list.getSongs()) {
                    resolvedHashes.add(song.getHash());
                }
                List<Integer> hashes = playlistRowHashes(resolvedHashes, list.getUnresolvedHashes());
                if (!hashes.isEmpty()) {
                    List<Object[]> argsList = new ArrayList<>(hashes.size());
                    for (int i = 0; i < hashes.size(); i++) {
                        argsList.add(new Object[]{list.getId(), hashes.get(i), i});
                    }
                    this.updateBatch(connection, "INSERT INTO playlist_song (playlists_id, song_hash,pos) values (?,?,?)", argsList);
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    MusicBox.getInstance().getLogger().warning("回滚事务失败: " + rollbackEx.getMessage());
                }
                throw new RuntimeException(e);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void updateBatch(Connection connection, @Language(value = "SQL") String query, List<Object[]> argsList) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(resolveTableNames(query))) {
            for (Object[] objects : argsList) {
                for (int i = 0; i < objects.length; i++) {
                    AbstractBase.setValue(statement, i, objects[i]);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<PlayerPlayListModel> extractPlayList(List<ResultSetRow> set) {
        HashMap<Integer, PlayerPlayListModel> modelMap = new HashMap<>();
        for (ResultSetRow row : set) {
            int id = row.getInt("id");
            PlayerPlayListModel model = modelMap.computeIfAbsent(id,
                k -> new PlayerPlayListModel(id, UUID.fromString(row.getString("owner")), row.getString("name")));
            // The queries below LEFT JOIN, so a playlist that has no songs yet still produces one row
            // with no hash. It exists, and it has to stay visible (and findable by name, which is how
            // creating a second one with the same name is rejected).
            Integer hash = row.getInt("song_hash");
            if (hash == null) {
                continue;
            }
            Optional<MusicBoxSong> song = MusicBoxSongManager.findSongByHash(hash);
            if (song.isPresent()) {
                model.getSongs().add(song.get());
            } else {
                // Kept on the model even though it cannot be shown, because it is about to be
                // written back: see playlistRowHashes.
                model.getUnresolvedHashes().add(hash);
            }
        }
        List<PlayerPlayListModel> list = new ArrayList<>(modelMap.values());
        // Never DELETE a playlist as a side effect of reading it. findSongByHash returns empty
        // whenever a hash isn't in the current in-memory song map -- e.g. during a /reload window
        // before songs finish (re)loading, or after a .nbs file was removed/renamed -- even though
        // the playlist_song rows still exist in the DB. Deleting here would let an ordinary read
        // (opening the playlist GUI) silently and irreversibly destroy players' saved playlists.
        // Only hide the currently-unresolvable ones from the returned list; they reappear once
        // their songs load again. (Empty rows are pruned only via an explicit deleteMe path.)
        //
        // A playlist with NO rows at all is not the same thing: it was just created and has simply
        // not been given a song yet. It has no unresolved hashes, so it stays in the list instead of
        // becoming invisible (and duplicate-create-able) until the first song is added.
        list.removeIf(l -> l.getSongs().isEmpty() && !l.getUnresolvedHashes().isEmpty());
        return list;
    }

    // The song rows to write for a playlist, in order.
    //
    // The whole point of this method is that the second list exists. savePlayList rewrites the
    // playlist's rows from scratch -- DELETE then INSERT -- so anything not in the returned list is
    // gone from the database. Building the list from the resolvable songs alone therefore deleted
    // every song whose file was, at that moment, renamed, removed or still loading: the read path
    // above goes to real trouble to preserve those rows, and the write path used to throw them away.
    // That is silent, permanent data loss triggered by an unrelated edit -- adding one song to a
    // playlist rewrites all of it -- and the comment above promised the opposite.
    //
    // A song the user actually removed is in neither list, which is what makes this a merge rather
    // than "keep everything".
    //
    // Unresolvable hashes are appended rather than restored to their original positions. They are
    // invisible in the GUI, so the only thing their position could affect is the order they reappear
    // in if their songs come back; keeping the data at all is what matters.
    static List<Integer> playlistRowHashes(List<Integer> resolvedHashes, List<Integer> unresolvedHashes) {
        List<Integer> hashes = new ArrayList<>(resolvedHashes.size() + unresolvedHashes.size());
        hashes.addAll(resolvedHashes);
        hashes.addAll(unresolvedHashes);
        return hashes;
    }

    public List<PlayerPlayListModel> getPlayLists(UUID playerUUID) {
        List<ResultSetRow> result = this.query(
            "SELECT p.id, p.owner, p.name, ps.song_hash\n" +
            "from playlists p\n" +
            "left join playlist_song ps on ps.playlists_id = p.id\n" +
            "where p.owner = ? order by ps.pos",
            playerUUID.toString()
        );
        return this.extractPlayList(result);
    }

    public void deleteMe(PlayerPlayListModel model) {
        try (Connection connection = this.getConnection()) {
            connection.setAutoCommit(false);
            try {
                this.update(connection, "DELETE from playlists where id = ?", model.getId());
                this.update(connection, "DELETE from playlist_song where playlists_id = ?", model.getId());
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    MusicBox.getInstance().getLogger().warning("回滚事务失败: " + rollbackEx.getMessage());
                }
                throw new RuntimeException(e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public PlayerPlayListModel getPlayListById(int id) {
        List<ResultSetRow> result = this.query(
            "SELECT p.id, p.owner, p.name, ps.song_hash\n" +
            "from playlists p\n" +
            "left join playlist_song ps on ps.playlists_id = p.id\n" +
            "where p.id = ? order by ps.pos",
            id
        );
        List<PlayerPlayListModel> list = this.extractPlayList(result);
        return list.isEmpty() ? null : list.get(0);
    }

    public PlayerPlayListModel createPlayList(UUID owner, String name) {
        PlayerPlayListModel playlist = new PlayerPlayListModel(-1, owner, name);
        this.savePlayList(playlist);
        return playlist;
    }

    public List<Location> getPreventedSigns() {
        try {
            List<ResultSetRow> result = this.query("SELECT location FROM signs");
            List<Location> locations = new ArrayList<>();
            for (ResultSetRow row : result) {
                String locStr = row.getString("location");
                Location loc = BukkitUtils.parseLocation(locStr);
                if (loc != null) {
                    locations.add(loc);
                }
            }
            return locations;
        } catch (Exception e) {
            if (isMissingTableError(e)) {
                return Collections.emptyList();
            }
            MusicBox.getInstance().getLogger().warning("获取告示牌列表失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // Shared by the write paths below: a transaction that failed must not leave the connection
    // half-way through one, and a rollback failure is worth a log but must not hide the original.
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            MusicBox.getInstance().getLogger().warning("回滚事务失败: " + rollbackEx.getMessage());
        }
    }

    /**
     * Replaces the protected-sign list in one transaction.
     *
     * <p>Throws when the write failed. Every caller already wraps this in a try/catch and treats
     * "returned normally" as "committed"; logging the error here and returning made those handlers
     * dead code and reported a failed save as a successful one.
     */
    public void savePreventedSigns(Collection<Location> locations) {
        try (Connection connection = this.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                this.update(connection, "DELETE FROM signs");
                List<Object[]> rows = new ArrayList<>(locations.size());
                for (Location loc : locations) {
                    rows.add(new Object[]{BukkitUtils.locationToString(loc)});
                }
                this.updateBatch(connection, "INSERT INTO signs (location) VALUES (?)", rows);
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw new RuntimeException("保存告示牌列表失败: " + e.getMessage(), e);
            } finally {
                restoreAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存告示牌列表失败: " + e.getMessage(), e);
        }
    }

    public void saveSignSong(Location location, int songHash) {
        try (Connection connection = this.getConnection()) {
            String locStr = BukkitUtils.locationToString(location);
            String sql = getUpsertSql("sign_songs", new String[]{"location", "song_hash"}, new String[]{"location"});
            this.update(connection, sql, locStr, songHash);
        } catch (SQLException e) {
            if (isMissingTableError(e)) {
                this.ensureTablesExist();
                try (Connection connection = this.getConnection()) {
                    String locStr = BukkitUtils.locationToString(location);
                    String sql = getUpsertSql("sign_songs", new String[]{"location", "song_hash"}, new String[]{"location"});
                    this.update(connection, sql, locStr, songHash);
                } catch (SQLException ex) {
                    throw new RuntimeException("保存告示牌歌曲失败: " + ex.getMessage(), ex);
                }
            } else {
                throw new RuntimeException("保存告示牌歌曲失败: " + e.getMessage(), e);
            }
        }
    }

    @Nullable
    public Integer getSignSong(Location location) {
        try {
            String locStr = BukkitUtils.locationToString(location);
            List<ResultSetRow> result = this.query("SELECT song_hash FROM sign_songs WHERE location = ?", locStr);
            if (!result.isEmpty()) {
                return result.get(0).getInt("song_hash");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteSignSong(Location location) {
        try (Connection connection = this.getConnection()) {
            String locStr = BukkitUtils.locationToString(location);
            this.update(connection, "DELETE FROM sign_songs WHERE location = ?", locStr);
        } catch (SQLException e) {
            if (!isMissingTableError(e)) {
                MusicBox.getInstance().getLogger().warning("删除告示牌歌曲记录失败: " + e.getMessage());
            }
        }
    }


    /**
     * The player's recent-song hashes, newest last.
     *
     * <p>A read failure is thrown, not turned into an empty list: the caller clears its in-memory
     * list and marks it loaded when this returns empty, and the next shutdown save would then write
     * that empty list back, turning a transient read error into a lost history.
     */
    public List<Integer> getRecentSongs(UUID playerUuid) {
        List<ResultSetRow> result = this.query(
                "SELECT song_hash FROM recent_songs WHERE player_uuid = ? ORDER BY pos ASC",
                playerUuid.toString());
        return result.stream().map(row -> row.getInt("song_hash")).collect(Collectors.toList());
    }

    public void clearRecentSongs(UUID playerUuid) {
        try (Connection connection = this.getConnection()) {
            this.update(connection, "DELETE FROM recent_songs WHERE player_uuid = ?", playerUuid.toString());
        } catch (SQLException e) {
            if (!isMissingTableError(e)) {
                MusicBox.getInstance().getLogger().warning("清空最近播放歌曲失败: " + e.getMessage());
            }
        }
    }


    public void saveRecentSongsBatch(UUID playerUuid, List<Integer> songHashes) {
        try {
            saveRecentSongsTransactional(playerUuid, songHashes);
        } catch (SQLException e) {
            if (isMissingTableError(e)) {
                this.ensureTablesExist();
                try {
                    saveRecentSongsTransactional(playerUuid, songHashes);
                } catch (SQLException ex) {
                    throw new RuntimeException("批量保存最近播放歌曲失败: " + ex.getMessage(), ex);
                }
            } else {
                throw new RuntimeException("批量保存最近播放歌曲失败: " + e.getMessage(), e);
            }
        }
    }

    private void saveRecentSongsTransactional(UUID playerUuid, List<Integer> songHashes) throws SQLException {
        // DELETE + re-insert must be one transaction; otherwise a crash between them
        // leaves the player's recent songs partially written (or fully wiped).
        try (Connection connection = this.getConnection()) {
            connection.setAutoCommit(false);
            try {
                this.update(connection, "DELETE FROM recent_songs WHERE player_uuid = ?", playerUuid.toString());
                String upsertSql = getUpsertSql("recent_songs", new String[]{"player_uuid", "song_hash", "pos"}, new String[]{"player_uuid", "song_hash"});
                // One prepared statement for the lot. The list runs to maxRecentSongs (up to 100),
                // and update() prepares a fresh statement per call.
                List<Object[]> rows = new ArrayList<>(songHashes.size());
                for (int i = 0; i < songHashes.size(); i++) {
                    rows.add(new Object[]{playerUuid.toString(), songHashes.get(i), i});
                }
                this.updateBatch(connection, upsertSql, rows);
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            }
        }
    }

    public void savePlayerVolume(UUID playerUuid, int volume) {
        try (Connection connection = this.getConnection()) {
            String sql = getUpsertSql("player_volumes", new String[]{"player_uuid", "volume"}, new String[]{"player_uuid"});
            this.update(connection, sql, playerUuid.toString(), volume);
        } catch (SQLException e) {
            if (isMissingTableError(e)) {
                this.ensureTablesExist();
                try (Connection connection = this.getConnection()) {
                    String sql = getUpsertSql("player_volumes", new String[]{"player_uuid", "volume"}, new String[]{"player_uuid"});
                    this.update(connection, sql, playerUuid.toString(), volume);
                } catch (SQLException ex) {
                    throw new RuntimeException("保存玩家音量失败: " + ex.getMessage(), ex);
                }
            } else {
                throw new RuntimeException("保存玩家音量失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Writes every entry in one transaction.
     *
     * <p>Throws when the batch failed; {@code VolumeManager} keeps the map dirty and retries on the
     * next sweep only because the failure is observable here.
     */
    public void savePlayerVolumesBatch(Map<UUID, Integer> volumes) {
        if (volumes == null || volumes.isEmpty()) {
            return;
        }
        try {
            savePlayerVolumesBatchInternal(volumes);
        } catch (SQLException e) {
            if (isMissingTableError(e)) {
                this.ensureTablesExist();
                try {
                    savePlayerVolumesBatchInternal(volumes);
                } catch (SQLException ex) {
                    throw new RuntimeException("批量保存玩家音量失败: " + ex.getMessage(), ex);
                }
            } else {
                throw new RuntimeException("批量保存玩家音量失败: " + e.getMessage(), e);
            }
        }
    }

    private void savePlayerVolumesBatchInternal(Map<UUID, Integer> volumes) throws SQLException {
        String sql = getUpsertSql("player_volumes", new String[]{"player_uuid", "volume"}, new String[]{"player_uuid"});
        List<Object[]> args = new ArrayList<>(volumes.size());
        for (Map.Entry<UUID, Integer> entry : volumes.entrySet()) {
            args.add(new Object[]{entry.getKey().toString(), entry.getValue()});
        }
        try (Connection connection = this.getConnection()) {
            connection.setAutoCommit(false);
            try {
                this.updateBatch(connection, sql, args);
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            }
        }
    }

    @Nullable
    public Integer getPlayerVolume(UUID playerUuid) {
        // null means "no stored row", not "read failed". VolumeManager must observe a failure
        // so it never persists its default placeholder over an unread stored value.
        List<ResultSetRow> result = this.query("SELECT volume FROM player_volumes WHERE player_uuid = ?",
            playerUuid.toString());
        return result.isEmpty() ? null : result.get(0).getInt("volume");
    }

    public void deletePlayerVolume(UUID playerUuid) {
        try (Connection connection = this.getConnection()) {
            this.update(connection, "DELETE FROM player_volumes WHERE player_uuid = ?", playerUuid.toString());
        } catch (SQLException e) {
            if (!isMissingTableError(e)) {
                MusicBox.getInstance().getLogger().warning("删除玩家音量记录失败: " + e.getMessage());
            }
        }
    }

    public void saveBlockPlayerVolume(Location location, int volume) {
        try (Connection connection = this.getConnection()) {
            String sql = getUpsertSql("block_player_volumes", new String[]{"location", "volume"}, new String[]{"location"});
            this.update(connection, sql, BukkitUtils.locationToString(location), volume);
        } catch (SQLException e) {
            if (isMissingTableError(e)) {
                this.ensureTablesExist();
                try (Connection connection = this.getConnection()) {
                    String sql = getUpsertSql("block_player_volumes", new String[]{"location", "volume"}, new String[]{"location"});
                    this.update(connection, sql, BukkitUtils.locationToString(location), volume);
                } catch (SQLException ex) {
                    throw new RuntimeException("保存方块播放器音量失败: " + ex.getMessage(), ex);
                }
            } else {
                throw new RuntimeException("保存方块播放器音量失败: " + e.getMessage(), e);
            }
        }
    }

    @Nullable
    public Integer getBlockPlayerVolume(Location location) {
        try {
            List<ResultSetRow> result = this.query("SELECT volume FROM block_player_volumes WHERE location = ?",
                BukkitUtils.locationToString(location));
            if (!result.isEmpty()) {
                return result.get(0).getInt("volume");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteBlockPlayerVolume(Location location) {
        try (Connection connection = this.getConnection()) {
            this.update(connection, "DELETE FROM block_player_volumes WHERE location = ?",
                BukkitUtils.locationToString(location));
        } catch (SQLException e) {
            if (!isMissingTableError(e)) {
                MusicBox.getInstance().getLogger().warning("删除方块播放器音量失败: " + e.getMessage());
            }
        }
    }

    public Connection openConnection() throws SQLException {
        return this.getConnection();
    }

    public List<ResultSetRow> executeQuery(String query, Object... args) {
        return this.query(query, args);
    }

    /** Public counterpart of {@link #streamQuery} for callers outside this package. */
    public void executeStreamQuery(String query, RowConsumer consumer, Object... args) {
        this.streamQuery(query, consumer, args);
    }

    public int executeUpdate(String query, Object... args) {
        return this.update(query, args);
    }

    public int executeUpdate(Connection connection, String query, Object... args) throws SQLException {
        return this.update(connection, query, args);
    }
}
