package com.huidu.musicboxplus.core.db.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered migration registry.
 *
 * Append new migrations; never edit or reorder a shipped one, because databases in the field
 * have already run it and the version row is the only record of that. The version numbers are
 * required to be contiguous starting at BASELINE_VERSION + 1: a gap would be
 * silently skipped forever, and this is the one place where a typo cannot be caught later.
 */
public final class Migrations {

    /**
     * Nothing yet. Version 1 is the baseline produced by db/*.sql; the first real change to
     * the table layout gets version 2 and is appended here.
     */
    private static final List<Migration> ALL = List.of();

    static {
        validate(ALL);
    }

    public static List<Migration> all() {
        return ALL;
    }

    /** Version a database must be at after this build has started successfully. */
    public static int latestVersion() {
        return ALL.isEmpty() ? Migration.BASELINE_VERSION : ALL.get(ALL.size() - 1).version();
    }

    /**
     * Migrations that still have to run, in order. A database ahead of this build (older plugin,
     * newer database) yields an empty list here and is rejected by the migrator instead of being
     * silently downgraded.
     */
    public static List<Migration> pending(int currentVersion, List<Migration> migrations) {
        validate(migrations);
        List<Migration> pending = new ArrayList<>();
        for (Migration migration : migrations) {
            if (migration.version() > currentVersion) {
                pending.add(migration);
            }
        }
        return List.copyOf(pending);
    }

    static void validate(List<Migration> migrations) {
        int expected = Migration.BASELINE_VERSION + 1;
        for (Migration migration : migrations) {
            if (migration.version() != expected) {
                throw new IllegalStateException("Migration versions must be contiguous from " + expected
                        + " but found " + migration.version() + " (" + migration.description() + ")");
            }
            expected++;
        }
    }

    private Migrations() {
    }
}
