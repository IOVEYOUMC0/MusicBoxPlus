package com.huidu.musicboxplus.core.db.schema;

import java.util.List;

/**
 * One ordered, re-runnable schema change.
 *
 * Version 1 is the baseline: the full creation script that AbstractBase already runs is idempotent,
 * so both a fresh database and one created before this class existed reach version 1 without any
 * migration. Everything after that is an explicit entry here.
 *
 * Statements must be safe to re-run. MySQL commits DDL implicitly, so a migration that fails halfway
 * leaves earlier statements applied while the version row still points at the old version; the next
 * startup runs the whole migration again. Write CREATE TABLE IF NOT EXISTS, and check before adding a
 * column or index, instead of assuming a single clean run.
 *
 * Two backends ship the plugin, and their DDL differs (column types, index syntax), so each migration
 * carries both statement lists instead of guessing a common dialect.
 */
public record Migration(int version, String description,
                        List<String> sqliteStatements, List<String> mysqlStatements) {

    /** The highest version the baseline script already produces. */
    public static final int BASELINE_VERSION = 1;

    public Migration {
        if (version <= BASELINE_VERSION) {
            throw new IllegalArgumentException(
                    "Migration versions start after the baseline (" + BASELINE_VERSION + "): " + version);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A migration needs a description: v" + version);
        }
        sqliteStatements = immutableNonBlank(sqliteStatements, "sqlite", version);
        mysqlStatements = immutableNonBlank(mysqlStatements, "mysql", version);
    }

    private static List<String> immutableNonBlank(List<String> statements, String dialect, int version) {
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("Migration v" + version + " has no " + dialect + " statements");
        }
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                throw new IllegalArgumentException("Migration v" + version + " has a blank " + dialect + " statement");
            }
            // The script splitter is not involved here: these strings go straight to Statement.execute.
            // A trailing semicolon would therefore be sent to the driver as part of the statement.
            if (statement.stripTrailing().endsWith(";")) {
                throw new IllegalArgumentException(
                        "Migration v" + version + " " + dialect + " statement must not end with ';'");
            }
        }
        return List.copyOf(statements);
    }

    public List<String> statements(boolean mysql) {
        return mysql ? mysqlStatements : sqliteStatements;
    }
}
