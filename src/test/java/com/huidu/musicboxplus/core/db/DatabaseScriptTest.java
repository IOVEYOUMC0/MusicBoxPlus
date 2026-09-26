package com.huidu.musicboxplus.core.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The shipped creation scripts are executed statement by statement at startup, so a script that
// splits wrongly takes the whole plugin down with "Could not connect to database" -- which is what
// a comment containing a semicolon did: the tail was handed to SQLite as if it were SQL.
//
// This only proves the split produced well-formed statements; it does not execute them (no live
// DB here), so a syntax error inside a statement is not caught by this test.
class DatabaseScriptTest {

    @Test
    void scriptsSplitIntoExecutableStatements() throws Exception {
        for (String name : List.of("SQLite", "MySQL")) {
            Path script = Path.of("src", "main", "resources", "db", name + ".sql");
            List<String> statements = AbstractBase.splitScript(Files.readString(script));

            assertFalse(statements.isEmpty(), name + ".sql produced no statements");
            for (String sql : statements) {
                String head = sql.toUpperCase(Locale.ROOT);
                assertTrue(head.startsWith("CREATE") || head.startsWith("ALTER")
                                || head.startsWith("DROP") || head.startsWith("INSERT")
                                || head.startsWith("PRAGMA") || head.startsWith("SET"),
                        name + ".sql: this is not a statement, so the script was split in the "
                                + "wrong place -- check for a ';' inside a comment:\n" + sql);
            }
        }
    }
}
