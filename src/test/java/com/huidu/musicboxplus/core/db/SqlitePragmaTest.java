package com.huidu.musicboxplus.core.db;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// setConnectionInitSql takes ONE statement. Hikari hands the string to Statement.execute(), and
// sqlite-jdbc runs only the first statement in it and discards the rest without an error.
//
// Measured on sqlite-jdbc 3.49.1 with the four PRAGMAs joined by semicolons: journal_mode came out
// WAL (the first one), but synchronous stayed FULL, foreign_keys stayed off, and busy_timeout
// stayed at the 3000 ms default instead of 10000. An fsync on every write is exactly the cost WAL
// is turned on to avoid, and nothing anywhere reports that it happened -- which is why this is
// pinned in a test rather than left to a comment.
class SqlitePragmaTest {

    private static final Path SOURCE =
            Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "db", "types", "SQLite.java");

    @Test
    void pragmasGoThroughDriverPropertiesNotAMultiStatementInitSql() throws Exception {
        String source = Files.readString(SOURCE);

        int initSql = source.indexOf("setConnectionInitSql");
        if (initSql >= 0) {
            String statement = source.substring(initSql, source.indexOf(';', initSql));
            assertFalse(statement.chars().filter(c -> c == ';').count() > 0,
                    "setConnectionInitSql runs only its FIRST statement; the rest are dropped silently");
        }

        for (String pragma : new String[]{"journal_mode", "synchronous", "foreign_keys", "busy_timeout"}) {
            assertTrue(source.contains("addDataSourceProperty(\"" + pragma + "\""),
                    pragma + " must be set as a driver property, or it will not take effect");
        }
    }
}
