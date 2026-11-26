package com.huidu.musicboxplus.core.db.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Brings an existing database up to latestVersion().
 *
 * Before this class the plugin only ran CREATE TABLE IF NOT EXISTS, which can add a missing table but
 * can never change one. Any column addition or type change would have silently produced a database
 * the code no longer understood. The version row fixes that: it records what has already been applied
 * so each change runs exactly once.
 *
 * Two properties matter more than elegance here. First, a database ahead of the plugin is an error,
 * not something to "fix": someone downgraded the jar, and running older code against a newer schema
 * corrupts data quietly, so startup aborts with a message naming both versions. Second, the version
 * only advances after every statement of a migration succeeded, so a failed migration retries on the
 * next start rather than being skipped.
 *
 * The JDBC work is isolated behind Database so the state machine can be tested without a
 * driver (sqlite-jdbc is provided by the server at runtime, not by the test classpath).
 */
public final class SchemaMigrator {

    /** Kept out of the creation scripts: it is bookkeeping for this class, not plugin data. */
    public static final String VERSION_TABLE = "musicbox_schema_version";

    /** Result of a successful run, for the caller to log. */
    public record Result(int previousVersion, int finalVersion, List<Migration> applied,
                         boolean adoptedBaseline) {
        public Result {
            applied = List.copyOf(applied);
        }
    }

    /**
     * Storage seam. RuntimeException is allowed through for driver-level failures; the
     * public entry point wraps everything into an unchecked exception carrying the version.
     */
    interface Database {
        void ensureVersionTable() throws SQLException;

        /** 0 means "no version recorded yet" (fresh database, or one predating this class). */
        int readVersion() throws SQLException;

        void writeVersion(int version) throws SQLException;

        void execute(String sql) throws SQLException;
    }

    public static Result migrate(Connection connection, UnaryOperator<String> resolveTableNames,
                                 boolean mysql, int currentVersionHint) {
        return migrate(connection, resolveTableNames, mysql, currentVersionHint, Migrations.all());
    }

    static Result migrate(Connection connection, UnaryOperator<String> resolveTableNames, boolean mysql,
                          int currentVersionHint, List<Migration> migrations) {
        return run(new JdbcDatabase(connection, resolveTableNames, mysql), mysql, currentVersionHint, migrations);
    }

    /**
     * Runs the pending migrations. currentVersionHint is used only when the version table is empty and
     * the caller already knows the database is not new; 0 is safe for every other case.
     */
    static Result run(Database database, boolean mysql, int currentVersionHint, List<Migration> migrations) {
        Migrations.validate(migrations);
        try {
            database.ensureVersionTable();
            int previous = database.readVersion();
            boolean adoptedBaseline = false;
            if (previous == 0) {
                // Fresh install or a database from before version tracking. Both already match the
                // baseline creation script, which the caller ran immediately before this method.
                previous = Math.max(Migration.BASELINE_VERSION, currentVersionHint);
                database.writeVersion(previous);
                adoptedBaseline = true;
            }

            int latest = migrations.isEmpty()
                    ? Migration.BASELINE_VERSION
                    : migrations.get(migrations.size() - 1).version();
            if (previous > latest) {
                throw new IllegalStateException("Database schema version " + previous
                        + " is newer than this plugin build supports (" + latest
                        + "). Refusing to start against a newer schema; upgrade the plugin or restore a backup.");
            }

            List<Migration> pending = Migrations.pending(previous, migrations);
            List<Migration> applied = new ArrayList<>();
            for (Migration migration : pending) {
                apply(database, migration, mysql);
                // Only after every statement succeeded: a half-applied migration must be retried,
                // not skipped.
                database.writeVersion(migration.version());
                applied.add(migration);
            }
            return new Result(previous, applied.isEmpty() ? previous : applied.get(applied.size() - 1).version(),
                    applied, adoptedBaseline);
        } catch (SQLException e) {
            throw new IllegalStateException("Database schema migration failed: " + e.getMessage(), e);
        }
    }

    private static void apply(Database database, Migration migration, boolean mysql) throws SQLException {
        for (String statement : migration.statements(mysql)) {
            database.execute(statement);
        }
    }

    private static final class JdbcDatabase implements Database {
        private final Connection connection;
        private final UnaryOperator<String> resolveTableNames;
        private final boolean mysql;

        private JdbcDatabase(Connection connection, UnaryOperator<String> resolveTableNames, boolean mysql) {
            this.connection = connection;
            this.resolveTableNames = resolveTableNames;
            this.mysql = mysql;
        }

        @Override
        public void ensureVersionTable() throws SQLException {
            String versionType = mysql ? "INT NOT NULL" : "INTEGER NOT NULL";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table() + " (version " + versionType + ")");
            }
        }

        @Override
        public int readVersion() throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT version FROM " + table())) {
                // The table holds at most one row; a stray extra row is a bug, not something to
                // average out, so take the highest and let the caller's compare catch nonsense.
                int version = 0;
                while (rows.next()) {
                    version = Math.max(version, rows.getInt(1));
                }
                return version;
            }
        }

        @Override
        public void writeVersion(int version) throws SQLException {
            // Replace rather than update: an empty or duplicated table then converges on one row.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + table());
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + table() + " (version) VALUES (?)")) {
                insert.setInt(1, version);
                insert.executeUpdate();
            }
        }

        @Override
        public void execute(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(resolveTableNames.apply(sql));
            }
        }

        private String table() {
            return resolveTableNames.apply(VERSION_TABLE);
        }
    }

    private SchemaMigrator() {
    }
}
