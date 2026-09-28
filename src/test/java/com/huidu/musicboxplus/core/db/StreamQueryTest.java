package com.huidu.musicboxplus.core.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.db.utils.ResultSetRow;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// AbstractBase.forEachRow (the core of streamQuery) and the extractSet that still uses it.
//
// The point of streamQuery is that player_music_notes -- one row per note of every player song --
// never exists as a list of rows. A fake ResultSet is enough to prove the ordering the fix depends
// on: the consumer is handed each row while next() is still mid-iteration, so a handler that keeps
// the row defeats the purpose and a handler that throws stops the read there.
class StreamQueryTest {

    private static final String[] COLUMNS = {"music_id", "pitch", "tick", "instruments"};

    @Test
    void handsEachRowToTheConsumerBeforeTheNextRowIsRead() throws SQLException {
        FakeResultSet fake = new FakeResultSet(List.of(
                row("a", 45, 0, "HARP"),
                row("a", 47, 5, "HARP,BELL"),
                row("b", 60, 10, null)));

        List<String> seen = new ArrayList<>();
        List<Integer> nextCallsWhenConsumed = new ArrayList<>();
        AbstractBase.forEachRow(fake.set(), r -> {
            nextCallsWhenConsumed.add(fake.nextCalls());
            seen.add(r.getString("music_id") + ":" + r.getInt("tick"));
        });

        assertEquals(List.of("a:0", "a:5", "b:10"), seen);
        // The discriminator: row N reaches the consumer after N calls to next(), not after all of
        // them. Collect-then-iterate would report 4, 4, 4 here and retain every row in between.
        assertEquals(List.of(1, 2, 3), nextCallsWhenConsumed,
                "rows must be handed over as they are read, not collected into a list first");
        assertEquals(fake.rowCount(), fake.nextCalls() - 1,
                "next() is called once per row plus the terminating call");
    }

    @Test
    void readsColumnsByNameFromTheResultSetMetadata() throws SQLException {
        FakeResultSet fake = new FakeResultSet(List.of(row("id-1", 66, 3, "FLUTE")));

        List<ResultSetRow> captured = new ArrayList<>();
        AbstractBase.forEachRow(fake.set(), captured::add);

        assertEquals(1, captured.size());
        assertEquals("id-1", captured.get(0).getString("music_id"));
        assertEquals(66, captured.get(0).getInt("pitch"));
        assertEquals(3, captured.get(0).getInt("tick"));
        assertEquals("FLUTE", captured.get(0).getString("instruments"));
    }

    @Test
    void nullColumnValuesArriveAsNull() throws SQLException {
        FakeResultSet fake = new FakeResultSet(List.of(row("a", 45, 0, null)));

        List<ResultSetRow> captured = new ArrayList<>();
        AbstractBase.forEachRow(fake.set(), captured::add);

        assertTrue(captured.get(0).hasKey("instruments"), "the column is present even when its value is null");
        assertNull(captured.get(0).getString("instruments"));
    }

    @Test
    void runsTheConsumerZeroTimesForAnEmptyResult() throws SQLException {
        FakeResultSet fake = new FakeResultSet(List.of());

        List<ResultSetRow> captured = new ArrayList<>();
        AbstractBase.forEachRow(fake.set(), captured::add);

        assertEquals(List.of(), captured);
    }

    @Test
    void consumerFailurePropagatesAndStopsReading() {
        FakeResultSet fake = new FakeResultSet(List.of(
                row("a", 45, 0, "HARP"),
                row("a", 47, 5, "HARP")));

        List<String> seen = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> AbstractBase.forEachRow(fake.set(), r -> {
            seen.add(r.getString("music_id"));
            throw new IllegalStateException("bad row");
        }));
        assertEquals(List.of("a"), seen, "the read must abort at the failing row, not continue");
        assertEquals(1, fake.nextCalls(), "no second next() after the consumer threw");
    }

    @Test
    void extractSetStillCollectsEveryRowInOrder() throws SQLException {
        FakeResultSet fake = new FakeResultSet(List.of(
                row("a", 45, 0, "HARP"),
                row("b", 60, 10, "BELL")));

        List<ResultSetRow> rows = AbstractBase.extractSet(fake.set());

        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).getString("music_id"));
        assertEquals("b", rows.get(1).getString("music_id"));
        assertEquals(fake.rowCount(), fake.nextCalls() - 1);
    }

    private static Map<String, Object> row(Object musicId, Object pitch, Object tick, Object instruments) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("music_id", musicId);
        values.put("pitch", pitch);
        values.put("tick", tick);
        values.put("instruments", instruments);
        return values;
    }

    /**
     * A ResultSet backed by a list of maps. Only the handful of methods forEachRow touches are
     * implemented; anything else fails loudly instead of silently returning null.
     */
    private static final class FakeResultSet implements InvocationHandler {

        private final List<Map<String, Object>> rows;
        private int cursor = -1;
        private int nextCalls;

        FakeResultSet(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        ResultSet set() {
            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, this);
        }

        int rowCount() {
            return rows.size();
        }

        int nextCalls() {
            return nextCalls;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "next":
                    nextCalls++;
                    cursor++;
                    return cursor < rows.size();
                case "getMetaData":
                    return metadata();
                case "getObject":
                    return rows.get(cursor).get((String) args[0]);
                case "toString":
                    return "FakeResultSet" + rows;
                default:
                    throw new UnsupportedOperationException("FakeResultSet does not implement " + method.getName());
            }
        }

        private ResultSetMetaData metadata() {
            return (ResultSetMetaData) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getColumnCount":
                                return COLUMNS.length;
                            case "getColumnName":
                                return COLUMNS[(Integer) args[0] - 1];
                            case "toString":
                                return "FakeResultSetMetaData";
                            default:
                                throw new UnsupportedOperationException(
                                        "FakeResultSetMetaData does not implement " + method.getName());
                        }
                    });
        }
    }
}
