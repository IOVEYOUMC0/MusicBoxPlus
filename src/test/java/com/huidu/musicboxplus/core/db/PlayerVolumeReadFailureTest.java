package com.huidu.musicboxplus.core.db;

import com.huidu.musicboxplus.core.db.utils.ResultSetRow;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerVolumeReadFailureTest {
    private static AbstractBase backend(List<ResultSetRow> rows, RuntimeException failure) {
        return new AbstractBase("SQLite") {
            @Override
            public boolean isMySQL() {
                return false;
            }

            @Override
            protected Connection acquireConnection() {
                throw new AssertionError("query is stubbed; no live DB/Bukkit access expected");
            }

            @Override
            protected List<ResultSetRow> query(String sql, Object... args) {
                assertEquals("SELECT volume FROM player_volumes WHERE player_uuid = ?", sql);
                assertEquals(1, args.length);
                if (failure != null) {
                    throw failure;
                }
                return rows;
            }
        };
    }

    @Test
    void failedQueryPropagatesInsteadOfMasqueradingAsMissingRow() {
        RuntimeException unavailable = new RuntimeException("connection unavailable");
        AbstractBase base = backend(Collections.emptyList(), unavailable);

        assertSame(unavailable,
                assertThrows(RuntimeException.class, () -> base.getPlayerVolume(UUID.randomUUID())));
    }

    @Test
    void successfulEmptyQueryStillMeansNoStoredVolume() {
        assertNull(backend(Collections.emptyList(), null).getPlayerVolume(UUID.randomUUID()));
    }

    @Test
    void successfulQueryReturnsStoredVolume() {
        ResultSetRow row = ResultSetRow.builder().addResultRow("volume", 45).build();
        assertEquals(45, backend(List.of(row), null).getPlayerVolume(UUID.randomUUID()));
    }
}
