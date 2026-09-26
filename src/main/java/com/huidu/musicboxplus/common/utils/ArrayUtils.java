package com.huidu.musicboxplus.common.utils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class ArrayUtils {

    public static <T> T[] removeFirst(Class<T> clazz, T[] source) {
        if (source.length == 0) {
            return source;
        }
        @SuppressWarnings("unchecked")
        T[] result = (T[]) Array.newInstance(clazz, source.length - 1);
        System.arraycopy(source, 1, result, 0, source.length - 1);
        return result;
    }

    public static List<String> replaceOrRemove(List<String> list, Map<String, String> replaceMap) {
        ArrayList<String> result = new ArrayList<>();
        for (String element : list) {
            if (element == null) {
                result.add(element);
                continue;
            }
            boolean shouldRemove = false;
            String processedElement = element;
            for (Map.Entry<String, String> entry : replaceMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (!element.contains(key)) continue;
                if (value == null || value.isEmpty()) {
                    shouldRemove = true;
                    break;
                }
                processedElement = processedElement.replace(key, value);
            }
            if (shouldRemove) continue;
            result.add(processedElement);
        }
        return result;
    }

    public static <T> T getRandom(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private ArrayUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}