package com.huidu.musicboxplus.core.db.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultSetRowTest {

    @Test
    void getIntHandlesAllNumericForms() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("int", 42)
                .addResultRow("long", 100L)
                .addResultRow("double", 3.7d)
                .addResultRow("string", "55")
                .addResultRow("garbage", "not a number")
                .addResultRow("missing", null)
                .build();

        assertEquals(42, row.getInt("int"));
        assertEquals(100, row.getInt("long"), "Long should be narrowed to int");
        assertEquals(3, row.getInt("double"), "Double should be truncated to int");
        assertEquals(55, row.getInt("string"));
        assertNull(row.getInt("garbage"), "unparseable string should return null, not throw");
        assertNull(row.getInt("missing"));
        assertNull(row.getInt("absent_key"));
    }

    @Test
    void getLongHandlesAllNumericForms() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("long", 5_000_000_000L)
                .addResultRow("int", 7)
                .addResultRow("string", "1234567890")
                .addResultRow("garbage", "abc")
                .addResultRow("missing", null)
                .build();

        assertEquals(5_000_000_000L, row.getLong("long"));
        assertEquals(7L, row.getLong("int"));
        assertEquals(1_234_567_890L, row.getLong("string"));
        assertNull(row.getLong("garbage"));
        assertNull(row.getLong("missing"));
    }

    @Test
    void getDoubleHandlesAllNumericForms() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("double", 1.5d)
                .addResultRow("int", 4)
                .addResultRow("string", "2.25")
                .addResultRow("garbage", "abc")
                .build();

        assertEquals(1.5d, row.getDouble("double"));
        assertEquals(4.0d, row.getDouble("int"));
        assertEquals(2.25d, row.getDouble("string"));
        assertNull(row.getDouble("garbage"));
    }

    @Test
    void getBooleanHandlesAllForms() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("boolTrue", true)
                .addResultRow("boolFalse", false)
                .addResultRow("intZero", 0)
                .addResultRow("intNonZero", 7)
                .addResultRow("stringTrue", "true")
                .addResultRow("stringFalse", "false")
                .addResultRow("missing", null)
                .build();

        assertTrue(row.getBoolean("boolTrue"));
        assertFalse(row.getBoolean("boolFalse"));
        assertFalse(row.getBoolean("intZero"), "Number 0 must be treated as false");
        assertTrue(row.getBoolean("intNonZero"), "Non-zero Number must be treated as true");
        assertTrue(row.getBoolean("stringTrue"));
        assertFalse(row.getBoolean("stringFalse"));
        assertNull(row.getBoolean("missing"));
    }

    @Test
    void getStringConvertsNonNullValuesViaToString() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("s", "hello")
                .addResultRow("i", 7)
                .addResultRow("n", null)
                .build();

        assertEquals("hello", row.getString("s"));
        assertEquals("7", row.getString("i"));
        assertNull(row.getString("n"));
        assertNull(row.getString("absent_key"));
    }

    @Test
    void hasKeyDistinguishesPresentNullFromAbsent() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("present_with_value", "v")
                .addResultRow("present_with_null", null)
                .build();

        assertTrue(row.hasKey("present_with_value"));
        assertTrue(row.hasKey("present_with_null"),
                "hasKey must return true for an explicitly-null mapping");
        assertFalse(row.hasKey("absent_key"));
    }

    @Test
    void isNullReportsTrueForNullValuesAndAbsentKeys() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("present", "v")
                .addResultRow("nullval", null)
                .build();

        assertFalse(row.isNull("present"));
        assertTrue(row.isNull("nullval"));
        assertTrue(row.isNull("absent_key"));
    }

    @Test
    void getObjectReturnsRawValue() {
        Object weirdValue = new Object();
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("raw", weirdValue)
                .build();
        assertEquals(weirdValue, row.getObject("raw"));
        assertNull(row.getObject("absent_key"));
    }

    @Test
    void positionalGetIntFindsRowsInInsertionOrder() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("first", 1)
                .addResultRow("second", 2)
                .addResultRow("third", "3")
                .build();

        // Note: the implementation only preserves order reliably with multiple entries
        // (single-entry uses singletonMap; many-entry uses LinkedHashMap).
        assertEquals(1, row.getInt(0));
        assertEquals(2, row.getInt(1));
        assertEquals(3, row.getInt(2));
        assertNull(row.getInt(99), "out-of-bounds index returns null instead of throwing");
    }

    @Test
    void builderResultMethodLoadsFromMap() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", 1);
        source.put("b", "two");

        ResultSetRow row = ResultSetRow.builder().result(source).build();
        assertEquals(1, row.getInt("a"));
        assertEquals("two", row.getString("b"));
    }

    @Test
    void builderResultMethodRejectsNullMap() {
        assertThrows(NullPointerException.class,
                () -> ResultSetRow.builder().result(null));
    }

    @Test
    void builderClearResultDropsAccumulatedEntries() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("a", 1)
                .addResultRow("b", 2)
                .clearResult()
                .addResultRow("c", 3)
                .build();

        assertFalse(row.hasKey("a"));
        assertFalse(row.hasKey("b"));
        assertTrue(row.hasKey("c"));
        assertEquals(3, row.getInt("c"));
    }

    @Test
    void emptyBuilderProducesUsableRow() {
        ResultSetRow row = ResultSetRow.builder().build();
        assertFalse(row.hasKey("anything"));
        assertNull(row.getInt("anything"));
        assertTrue(row.isNull("anything"));
    }

    @Test
    void singleEntryBuilderProducesUsableRow() {
        ResultSetRow row = ResultSetRow.builder()
                .addResultRow("only", 1)
                .build();
        assertTrue(row.hasKey("only"));
        assertEquals(1, row.getInt("only"));
    }

    @Test
    void directPackagePrivateConstructorWorks() {
        // Sanity check that the package-private constructor is reachable
        // from same-package tests and behaves identically to the builder path.
        Map<String, Object> map = new HashMap<>();
        map.put("x", 9);
        ResultSetRow row = new ResultSetRow(map);
        assertEquals(9, row.getInt("x"));
    }
}
