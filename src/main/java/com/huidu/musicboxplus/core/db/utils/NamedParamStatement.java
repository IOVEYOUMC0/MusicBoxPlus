package com.huidu.musicboxplus.core.db.utils;

import com.huidu.musicboxplus.core.db.AbstractBase;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Builds a PreparedStatement from :named placeholders. Collections expand in iteration order;
 * an empty collection binds one SQL NULL. Quoted strings/identifiers and comments are left intact.
 * This mutable builder is not thread-safe: do not share it between concurrent operations.
 */
public class NamedParamStatement {

    private final String query;
    private final Map<String, Collection<?>> boundValues = new HashMap<>();

    public NamedParamStatement(@Language("SQL") String query) {
        this.query = query;
    }

    private PreparedStatement grownStatement(Connection connection) throws SQLException {
        StringBuilder sql = new StringBuilder(query.length());
        List<Object> positional = new ArrayList<>();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;

        // Each input character is visited once. Expansion is linear in the number of bound values.
        for (int cursor = 0; cursor < query.length();) {
            char current = query.charAt(cursor);
            char next = cursor + 1 < query.length() ? query.charAt(cursor + 1) : 0;
            if (lineComment) {
                sql.append(current);
                cursor++;
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
            } else if (blockComment) {
                sql.append(current);
                cursor++;
                if (current == '*' && next == '/') {
                    sql.append(next);
                    cursor++;
                    blockComment = false;
                }
            } else if (quote != 0) {
                sql.append(current);
                cursor++;
                if (current == '\\' && cursor < query.length()) {
                    // MySQL backslash escaping (including escaped backslashes).
                    sql.append(query.charAt(cursor++));
                } else if (current == quote) {
                    if (next == quote) {
                        // SQL doubled delimiters: '', "", and ``.
                        sql.append(next);
                        cursor++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                sql.append(current);
                cursor++;
            } else if (current == '#' || (current == '-' && next == '-')) {
                lineComment = true;
                sql.append(current);
                cursor++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                sql.append(current).append(next);
                cursor += 2;
            } else if (current == ':' && (cursor == 0 || query.charAt(cursor - 1) != ':')
                    && isNameStart(next)) {
                int end = cursor + 2;
                while (end < query.length() && isNamePart(query.charAt(end))) {
                    end++;
                }
                String key = query.substring(cursor + 1, end);
                if (!boundValues.containsKey(key)) {
                    throw new IllegalArgumentException("Unbound named SQL parameter: :" + key);
                }
                Collection<?> values = boundValues.get(key);
                if (values == null || values.isEmpty()) {
                    sql.append('?');
                    positional.add(null);
                } else {
                    boolean first = true;
                    for (Object value : values) {
                        if (!first) {
                            sql.append(',');
                        }
                        first = false;
                        sql.append('?');
                        positional.add(value);
                    }
                }
                cursor = end;
            } else {
                sql.append(current);
                cursor++;
            }
        }

        PreparedStatement statement = connection.prepareStatement(sql.toString());
        try {
            for (int index = 0; index < positional.size(); index++) {
                AbstractBase.setValue(statement, index, positional.get(index));
            }
            boundValues.clear();
            return statement;
        } catch (SQLException | RuntimeException | Error failure) {
            // The caller's try-with-resources has not acquired this statement yet.
            try {
                statement.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private static boolean isNameStart(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z' || character == '_';
    }

    private static boolean isNamePart(char character) {
        return isNameStart(character) || character >= '0' && character <= '9';
    }

    // The two ownership-taking forms below were removed: they returned a ResultSet or a
    // PreparedStatement that the caller had to remember to close, and closing only the ResultSet
    // does not close its Statement. Callers use executeQueryAndClose, which owns both.
    public int executeUpdate(Connection connection) throws SQLException {
        try (PreparedStatement statement = grownStatement(connection)) {
            return statement.executeUpdate();
        }
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
        boundValues.put(key, Arrays.asList(values));
    }
}
