package com.huidu.musicboxplus.core.db.schema;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The migration state machine, exercised without a driver: sqlite-jdbc is supplied by the server at
// runtime and is not on the test classpath, so the JDBC adapter is the part these tests deliberately
// do not cover.
class SchemaMigratorTest {

    private static final Migration V2 = new Migration(2, "add a column",
            List.of("ALTER TABLE player_music ADD COLUMN mood TEXT"),
            List.of("ALTER TABLE player_music ADD COLUMN mood VARCHAR(32)"));
    private static final Migration V3 = new Migration(3, "index a column",
            List.of("CREATE INDEX IF NOT EXISTS idx_mood ON player_music (mood)"),
            List.of("CREATE INDEX idx_mood ON player_music (mood)"));

    @Test
    void freshDatabaseAdoptsTheBaselineAndRunsEveryMigration() {
        FakeDatabase database = new FakeDatabase(0);

        SchemaMigrator.Result result = SchemaMigrator.run(database, false, 0, List.of(V2, V3));

        assertTrue(result.adoptedBaseline(), "an unversioned database must be adopted, not migrated from scratch");
        assertEquals(Migration.BASELINE_VERSION, result.previousVersion());
        assertEquals(3, result.finalVersion());
        assertEquals(List.of(2, 3), result.applied().stream().map(Migration::version).toList());
        assertEquals(3, database.version, "the version row must end at the last applied migration");
        assertEquals(List.of(
                "ALTER TABLE player_music ADD COLUMN mood TEXT",
                "CREATE INDEX IF NOT EXISTS idx_mood ON player_music (mood)"), database.executed);
    }

    @Test
    void anUpToDateDatabaseExecutesNothing() {
        FakeDatabase database = new FakeDatabase(3);

        SchemaMigrator.Result result = SchemaMigrator.run(database, false, 0, List.of(V2, V3));

        assertFalse(result.adoptedBaseline(), "the version row already existed");
        assertEquals(3, result.previousVersion());
        assertTrue(result.applied().isEmpty());
        assertTrue(database.executed.isEmpty(), "restarting must not re-run applied migrations");
    }

    @Test
    void onlyMigrationsAheadOfTheDatabaseRun() {
        FakeDatabase database = new FakeDatabase(2);

        SchemaMigrator.Result result = SchemaMigrator.run(database, false, 0, List.of(V2, V3));

        assertEquals(2, result.previousVersion());
        assertEquals(List.of(3), result.applied().stream().map(Migration::version).toList());
        assertEquals(List.of("CREATE INDEX IF NOT EXISTS idx_mood ON player_music (mood)"), database.executed);
    }

    @Test
    void aDatabaseNewerThanThePluginIsRejectedInsteadOfDowngraded() {
        FakeDatabase database = new FakeDatabase(9);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SchemaMigrator.run(database, false, 0, List.of(V2, V3)));

        assertTrue(failure.getMessage().contains("newer than this plugin build"),
                "the operator needs to see why startup stopped: " + failure.getMessage());
        assertTrue(database.executed.isEmpty(), "a newer schema must not be written to at all");
        assertEquals(9, database.version);
    }

    @Test
    void aFailedMigrationLeavesTheVersionBehindSoItRetries() throws SQLException {
        FakeDatabase database = new FakeDatabase(0);
        database.failOn = "CREATE INDEX IF NOT EXISTS idx_mood ON player_music (mood)";

        assertThrows(IllegalStateException.class,
                () -> SchemaMigrator.run(database, false, 0, List.of(V2, V3)));

        assertEquals(2, database.version,
                "v2 succeeded and must be recorded; v3 failed and must stay pending for the next start");
        assertTrue(database.executed.contains("ALTER TABLE player_music ADD COLUMN mood TEXT"));
    }

    @Test
    void theMysqlDialectGetsItsOwnStatements() {
        FakeDatabase database = new FakeDatabase(1);

        SchemaMigrator.run(database, true, 0, List.of(V2, V3));

        assertEquals(List.of(
                "ALTER TABLE player_music ADD COLUMN mood VARCHAR(32)",
                "CREATE INDEX idx_mood ON player_music (mood)"), database.executed);
    }

    @Test
    void aCallerThatKnowsTheDatabaseIsNotNewCanSeedAHigherBaseline() {
        FakeDatabase database = new FakeDatabase(0);

        SchemaMigrator.run(database, false, 2, List.of(V2, V3));

        // The caller already knows the schema is at 2, so only v3 may run. This exists for a future
        // installer that stamps a version before the first tracked start.
        assertEquals(List.of("CREATE INDEX IF NOT EXISTS idx_mood ON player_music (mood)"), database.executed);
        assertEquals(3, database.version);
    }

    @Test
    void migrationVersionsMustBeContiguous() {
        Migration gap = new Migration(4, "skips v3", List.of("SELECT 1"), List.of("SELECT 1"));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Migrations.pending(1, List.of(V2, gap)));
        assertTrue(failure.getMessage().contains("contiguous"), failure.getMessage());
    }

    @Test
    void aMigrationCannotClaimTheBaselineVersionOrLackStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> new Migration(1, "baseline", List.of("SELECT 1"), List.of("SELECT 1")));
        assertThrows(IllegalArgumentException.class,
                () -> new Migration(2, " ", List.of("SELECT 1"), List.of("SELECT 1")));
        assertThrows(IllegalArgumentException.class,
                () -> new Migration(2, "empty", List.of(), List.of("SELECT 1")));
        assertThrows(IllegalArgumentException.class,
                () -> new Migration(2, "semicolon", List.of("SELECT 1;"), List.of("SELECT 1")));
    }

    @Test
    void theShippedRegistryIsValidAndStartsAtTheBaseline() {
        Migrations.validate(Migrations.all());
        assertTrue(Migrations.latestVersion() >= Migration.BASELINE_VERSION);
        assertTrue(Migrations.pending(Migration.BASELINE_VERSION, Migrations.all()).size()
                <= Migrations.all().size());
    }

    /** Records what the migrator asked for; `failOn` simulates a driver error on one statement. */
    private static final class FakeDatabase implements SchemaMigrator.Database {
        private final List<String> executed = new ArrayList<>();
        private int version;
        private String failOn;

        private FakeDatabase(int version) {
            this.version = version;
        }

        @Override
        public void ensureVersionTable() {
        }

        @Override
        public int readVersion() {
            return version;
        }

        @Override
        public void writeVersion(int version) {
            this.version = version;
        }

        @Override
        public void execute(String sql) throws SQLException {
            if (failOn != null && failOn.equals(sql)) {
                throw new SQLException("simulated failure: " + sql);
            }
            executed.add(sql);
        }
    }
}
