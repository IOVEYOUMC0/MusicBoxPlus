package com.huidu.musicboxplus.core.db.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NamedParamStatementTest {

    @Test
    void onlyRewritesRealParametersOutsideQuotedText() throws SQLException {
        String[] protectedText = {
                "'%:id%'",
                "'https://host:port/path/:id'",
                "\"column:id\"",
                "`column:id`",
                "'it''s :hidden'",
                "\"a\"\":hidden\"",
                "`a``:hidden`",
                "'back\\' :hidden'",
                "\"back\\\" :hidden\"",
                "`back\\` :hidden`",
                "'two backslashes\\\\'",
                "'-- :hidden /* :other */ # :last'"
        };
        for (String text : protectedText) {
            JdbcProbe jdbc = new JdbcProbe();
            NamedParamStatement statement = new NamedParamStatement("SELECT " + text + ", :id");
            statement.setValue("id", 42);

            assertEquals(7, statement.executeUpdate(jdbc.connection));

            assertEquals("SELECT " + text + ", ?", jdbc.sql, text);
            assertEquals(List.of(new Binding(1, 42)), jdbc.bindings, text);
            assertEquals(1, jdbc.statementCloses, text);
            assertEquals(0, jdbc.connectionCloses, "the caller owns the connection");
        }
    }

    @Test
    void skipsCommentsAndResumesAtBothLineEndings() throws SQLException {
        String query = "SELECT :a -- :ignored ' \" ` /*\r\n"
                + ", :b # :ignored ' /*\r"
                + ", :c /* :ignored ' -- # \" `\n :also_ignored */ , :d";
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement(query);
        statement.setValue("a", 1);
        statement.setValue("b", 2);
        statement.setValue("c", 3);
        statement.setValue("d", 4);

        statement.executeUpdate(jdbc.connection);

        assertEquals("SELECT ? -- :ignored ' \" ` /*\r\n"
                + ", ? # :ignored ' /*\r"
                + ", ? /* :ignored ' -- # \" `\n :also_ignored */ , ?", jdbc.sql);
        assertEquals(List.of(new Binding(1, 1), new Binding(2, 2),
                new Binding(3, 3), new Binding(4, 4)), jdbc.bindings);
    }

    @Test
    void ignoresUnterminatedQuotedTextAndCommentsWithoutInventingBindings() throws SQLException {
        for (String suffix : List.of("'unterminated :hidden", "\"unterminated :hidden",
                "`unterminated :hidden", "-- :hidden", "# :hidden", "/* :hidden")) {
            JdbcProbe jdbc = new JdbcProbe();
            NamedParamStatement statement = new NamedParamStatement("SELECT :id, " + suffix);
            statement.setValue("id", 9);

            statement.executeUpdate(jdbc.connection);

            assertEquals("SELECT ?, " + suffix, jdbc.sql);
            assertEquals(List.of(new Binding(1, 9)), jdbc.bindings);
        }
    }

    @Test
    void leavesCastsAndNonNamesAloneAndExpandsEveryRepeatedName() throws SQLException {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement(
                "SELECT :_id2::integer, value::text, :::cast, :9, :, :_id2, :next");
        statement.setValue("_id2", 8);
        statement.setValue("next", "last");

        statement.executeUpdate(jdbc.connection);

        assertEquals("SELECT ?::integer, value::text, :::cast, :9, :, ?, ?", jdbc.sql);
        assertEquals(List.of(new Binding(1, 8), new Binding(2, 8),
                new Binding(3, "last")), jdbc.bindings);
    }

    @Test
    void arraysKeepOrderDuplicatesAndNullsAtEveryOccurrence() throws SQLException {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement("SELECT :ids, :tail, :ids");
        statement.setValues("ids", new Integer[]{3, 1, 3, null, 2});
        statement.setValue("tail", "end");

        statement.executeUpdate(jdbc.connection);

        assertEquals("SELECT " + "?,?,?,?,?" + ", ?, " + "?,?,?,?,?", jdbc.sql);
        assertEquals(List.of(new Binding(1, 3), new Binding(2, 1), new Binding(3, 3),
                new Binding(4, null), new Binding(5, 2), new Binding(6, "end"),
                new Binding(7, 3), new Binding(8, 1), new Binding(9, 3),
                new Binding(10, null), new Binding(11, 2)), jdbc.bindings);
    }

    @Test
    void explicitNullAndEmptyCollectionsEachBindOneSqlNull() throws SQLException {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement("SELECT :nil, :empty, :array, :values");
        statement.setValue("nil", null);
        statement.setValues("empty", List.of());
        statement.setValues("array", new String[0]);
        statement.setValues("values", Arrays.asList("b", null, "b", "a"));

        statement.executeUpdate(jdbc.connection);

        assertEquals("SELECT ?, ?, ?, ?,?,?,?", jdbc.sql);
        assertEquals(List.of(new Binding(1, null), new Binding(2, null), new Binding(3, null),
                new Binding(4, "b"), new Binding(5, null), new Binding(6, "b"),
                new Binding(7, "a")), jdbc.bindings);
    }

    @Test
    void missingNameFailsClearlyBeforeAnyStatementIsPrepared() {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement("SELECT :bound, :missing");
        statement.setValue("bound", null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> statement.executeUpdate(jdbc.connection));

        assertTrue(failure.getMessage().contains(":missing"));
        assertEquals(0, jdbc.prepares);
    }

    @Test
    void successfulPreparationConsumesBindingsAndResetRemovesThem() throws SQLException {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement("SELECT :id");
        statement.setValue("id", 1);
        statement.executeUpdate(jdbc.connection);
        assertThrows(IllegalArgumentException.class, () -> statement.executeUpdate(jdbc.connection));
        assertEquals(1, jdbc.prepares);

        statement.setValue("id", 2);
        statement.reset();
        assertThrows(IllegalArgumentException.class, () -> statement.executeUpdate(jdbc.connection));
        assertEquals(1, jdbc.prepares);
    }

    @Test
    void sqlBindingFailureClosesStatementAndPreservesOriginalAndCloseFailure() {
        assertBindingFailure(new SQLException("bind failed"), new SQLException("close failed"));
    }

    @Test
    void runtimeBindingFailureClosesStatementAndPreservesOriginalAndCloseFailure() {
        assertBindingFailure(new IllegalStateException("bind failed"),
                new IllegalArgumentException("close failed"));
    }

    @Test
    void bindingFailureWithSuccessfulCloseStillPropagatesOriginal() {
        assertBindingFailure(new SQLException("bind failed"), null);
        assertBindingFailure(new IllegalStateException("bind failed"), null);
    }

    private static void assertBindingFailure(Exception original, Exception closeFailure) {
        JdbcProbe jdbc = new JdbcProbe();
        jdbc.bindingFailure = original;
        jdbc.closeFailure = closeFailure;
        NamedParamStatement statement = new NamedParamStatement("UPDATE t SET a=:a, b=:b");
        statement.setValue("a", 1);
        statement.setValue("b", 2);

        Exception thrown = assertThrows(original.getClass(), () -> statement.executeUpdate(jdbc.connection));

        assertSame(original, thrown);
        assertEquals(1, jdbc.statementCloses);
        assertEquals(0, jdbc.executions);
        assertEquals(List.of(new Binding(1, 1)), jdbc.bindings, "failure happens at the second binding");
        if (closeFailure == null) {
            assertEquals(0, thrown.getSuppressed().length);
        } else {
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(closeFailure, thrown.getSuppressed()[0]);
        }
    }

    @Test
    void queryClosesResultSetThenStatementWithoutClosingConnection() throws SQLException {
        JdbcProbe jdbc = new JdbcProbe();
        NamedParamStatement statement = new NamedParamStatement("SELECT :id");
        statement.setValue("id", 1);

        statement.executeQueryAndClose(jdbc.connection, result -> {
            assertSame(jdbc.resultSet, result);
            assertEquals(0, jdbc.statementCloses);
            assertFalse(jdbc.resultClosed);
        });

        assertEquals(List.of("result.close", "statement.close"), jdbc.closeOrder);
        assertEquals(0, jdbc.connectionCloses);
    }

    @Test
    void handlerAndExecutionFailuresStillCloseOwnedResources() {
        JdbcProbe handlerJdbc = new JdbcProbe();
        SQLException handlerFailure = new SQLException("handler");
        NamedParamStatement query = new NamedParamStatement("SELECT 1");
        assertSame(handlerFailure, assertThrows(SQLException.class,
                () -> query.executeQueryAndClose(handlerJdbc.connection, result -> {
                    throw handlerFailure;
                })));
        assertEquals(List.of("result.close", "statement.close"), handlerJdbc.closeOrder);

        JdbcProbe executeJdbc = new JdbcProbe();
        executeJdbc.executionFailure = new SQLException("execute");
        assertSame(executeJdbc.executionFailure, assertThrows(SQLException.class,
                () -> new NamedParamStatement("SELECT 1")
                        .executeQueryAndClose(executeJdbc.connection, result -> {})));
        assertEquals(1, executeJdbc.statementCloses);
        assertFalse(executeJdbc.resultClosed);
    }

    private record Binding(int index, Object value) {}

    /** Strict JDBC proxies: tests exercise the public API, including real prepare/bind ownership. */
    private static final class JdbcProbe {
        private String sql;
        private int prepares;
        private int executions;
        private int statementCloses;
        private int connectionCloses;
        private boolean resultClosed;
        private Exception bindingFailure;
        private Exception closeFailure;
        private SQLException executionFailure;
        private final List<Binding> bindings = new ArrayList<>();
        private final List<String> closeOrder = new ArrayList<>();

        private final ResultSet resultSet = proxy(ResultSet.class, (object, method, args) -> {
            if (method.getName().equals("close")) {
                resultClosed = true;
                closeOrder.add("result.close");
                return null;
            }
            throw new AssertionError("Unexpected ResultSet call: " + method);
        });

        private final PreparedStatement prepared = proxy(PreparedStatement.class, (object, method, args) -> {
            switch (method.getName()) {
                case "setObject":
                    if (bindingFailure != null && (Integer) args[0] == 2) {
                        throw bindingFailure;
                    }
                    bindings.add(new Binding((Integer) args[0], args[1]));
                    return null;
                case "executeUpdate":
                case "executeQuery":
                    executions++;
                    if (executionFailure != null) {
                        throw executionFailure;
                    }
                    return method.getName().equals("executeUpdate") ? 7 : resultSet;
                case "close":
                    statementCloses++;
                    closeOrder.add("statement.close");
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    return null;
                default:
                    throw new AssertionError("Unexpected PreparedStatement call: " + method);
            }
        });

        private final Connection connection = proxy(Connection.class, (object, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    prepares++;
                    sql = (String) args[0];
                    return prepared;
                case "close":
                    connectionCloses++;
                    return null;
                default:
                    throw new AssertionError("Unexpected Connection call: " + method);
            }
        });

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
