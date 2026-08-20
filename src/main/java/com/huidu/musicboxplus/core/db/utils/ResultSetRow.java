package com.huidu.musicboxplus.core.db.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResultSetRow {
    private final Map<String, Object> result;

    public Integer getInt(String key) {
        Object value = this.result.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getString(String key) {
        Object value = this.result.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    public Integer getInt(int i) {
        int k = -1;
        for (Map.Entry<String, Object> entry : this.result.entrySet()) {
            if (++k != i) continue;
            Object value = entry.getValue();
            if (value == null) {
                return null;
            }
            if (value instanceof Integer) {
                return (Integer) value;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public Long getLong(String key) {
        Object value = this.result.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    public Double getDouble(String key) {
        Object value = this.result.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    public Boolean getBoolean(String key) {
        Object value = this.result.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    public Object getObject(String key) {
        return this.result.get(key);
    }
    
    public boolean hasKey(String key) {
        return this.result.containsKey(key);
    }
    
    public boolean isNull(String key) {
        return this.result.get(key) == null;
    }

    ResultSetRow(Map<String, Object> result) {
        this.result = result;
    }

    public static ResultSetRowBuilder builder() {
        return new ResultSetRowBuilder();
    }

    public static class ResultSetRowBuilder {
        private ArrayList<String> result$key;
        private ArrayList<Object> result$value;

        ResultSetRowBuilder() {
        }

        public ResultSetRowBuilder addResultRow(String addResultRowKey, Object addResultRowValue) {
            if (this.result$key == null) {
                this.result$key = new ArrayList<>();
                this.result$value = new ArrayList<>();
            }
            this.result$key.add(addResultRowKey);
            this.result$value.add(addResultRowValue);
            return this;
        }

        public ResultSetRowBuilder result(Map<? extends String, ? extends Object> result) {
            if (result == null) {
                throw new NullPointerException("result cannot be null");
            }
            if (this.result$key == null) {
                this.result$key = new ArrayList<>();
                this.result$value = new ArrayList<>();
            }
            for (Map.Entry<? extends String, ? extends Object> $lombokEntry : result.entrySet()) {
                this.result$key.add($lombokEntry.getKey());
                this.result$value.add($lombokEntry.getValue());
            }
            return this;
        }

        public ResultSetRowBuilder clearResult() {
            if (this.result$key != null) {
                this.result$key.clear();
                this.result$value.clear();
            }
            return this;
        }

        public ResultSetRow build() {
            Map<String, Object> result;
            switch (this.result$key == null ? 0 : this.result$key.size()) {
                case 0: {
                    result = Collections.emptyMap();
                    break;
                }
                case 1: {
                    result = Collections.singletonMap(this.result$key.get(0), this.result$value.get(0));
                    break;
                }
                default: {
                    result = new LinkedHashMap<String, Object>(this.result$key.size() < 0x40000000 ? 1 + this.result$key.size() + (this.result$key.size() - 3) / 3 : Integer.MAX_VALUE);
                    for (int $i = 0; $i < this.result$key.size(); ++$i) {
                        result.put(this.result$key.get($i), this.result$value.get($i));
                    }
                    result = Collections.unmodifiableMap(result);
                }
            }
            return new ResultSetRow(result);
        }

        public String toString() {
            return "ResultSetRow.ResultSetRowBuilder(result$key=" + String.valueOf(this.result$key) + ", result$value=" + String.valueOf(this.result$value) + ")";
        }
    }
}
