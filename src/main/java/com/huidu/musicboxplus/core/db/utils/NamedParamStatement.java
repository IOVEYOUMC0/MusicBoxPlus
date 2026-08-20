package com.huidu.musicboxplus.core.db.utils;

import com.huidu.musicboxplus.core.db.AbstractBase;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Builds a PreparedStatement from a query that uses :named placeholders instead of
// positional '?'. A name bound to a collection expands into one placeholder per
// element, which is how IN (...) clauses are expressed without string concatenation.
public class NamedParamStatement {

    // Matches a :name token, but not one that sits inside a single-quoted literal.
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<![:'\\\\]):([A-Za-z_][A-Za-z0-9_]*)");

    private final String query;
    private final Map<String, Collection<?>> boundValues = new HashMap<>();

    public NamedParamStatement(@Language("SQL") String query) {
        this.query = query;
    }

    private PreparedStatement grownStatement(Connection connection) throws SQLException {
        Matcher matcher = PLACEHOLDER.matcher(query);
        StringBuilder sql = new StringBuilder(query.length());
        Deque<Object> positional = new ArrayDeque<>();
        int cursor = 0;

        while (matcher.find()) {
            sql.append(query, cursor, matcher.start());
            cursor = matcher.end();

            String key = matcher.group(1);
            Collection<?> values = boundValues.get(key);
            if (values == null || values.isEmpty()) {
                sql.append('?');
                positional.addLast(null);
                continue;
            }
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            positional.addAll(values);
        }
        sql.append(query, cursor, query.length());

        PreparedStatement statement = connection.prepareStatement(sql.toString());
        int index = 0;
        for (Object value : positional) {
            AbstractBase.setValue(statement, index, value);
            index++;
        }
        boundValues.clear();
        return statement;
    }

    public ResultSet executeQuery(Connection connection) throws SQLException {
        return grownStatement(connection).executeQuery();
    }

    public int executeUpdate(Connection connection) throws SQLException {
        try (PreparedStatement statement = grownStatement(connection)) {
            return statement.executeUpdate();
        }
    }

    public PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
        return grownStatement(connection);
    }

    public void executeQueryAndClose(Connection connection, ResultSetHandler handler) throws SQLException {
        try (PreparedStatement statement = grownStatement(connection);
             ResultSet rs = statement.executeQuery()) {
            handler.handle(rs);
        }
    }

    @FunctionalInterface
    public interface ResultSetHandler {
        void handle(ResultSet rs) throws SQLException;
    }

    public void reset() {
        boundValues.clear();
    }

    public void setValue(String key, Object value) {
        boundValues.put(key, Collections.singleton(value));
    }

    public <T> void setValues(String key, Collection<T> values) {
        boundValues.put(key, values);
    }

    public <T> void setValues(String key, T[] values) {
        Set<T> set = Arrays.stream(values).collect(Collectors.toSet());
        boundValues.put(key, set);
    }
}
