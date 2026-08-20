package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.common.lang.Lang;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class StringUtils {
    public static String getOrEmpty(String title, Supplier<String> getName) {
        if (title == null || title.isEmpty()) {
            return getName.get();
        }
        return title;
    }

    public static String t(String str) {
        return MiniMessageUtils.processText(str);
    }

    public static List<String> t(Collection<String> collection) {
        return collection.stream().map(StringUtils::t).collect(Collectors.toList());
    }

    public static String replace(String source, String ... replace) {
        if (replace.length > 0) {
            if (replace.length % 2 != 0) {
                throw new RuntimeException("Oooooooooops");
            }
            String str = source;
            for (int i = 1; i < replace.length; i += 2) {
                str = str.replace(replace[i - 1], replace[i]);
            }
            return str;
        }
        return source;
    }

    public static String replaceVariables(String source, Map<String, String> variables) {
        if (source == null || variables == null) {
            return source;
        }
        String result = source;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() == null) continue;
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static String toHumanTime(int second) {
        int min = (int)Math.floor((double)second / 60.0);
        int sec = second % 60;
        StringBuilder result = new StringBuilder();
        if (min > 0) {
            result.append(Lang.HUMAN_TIME_MINUTE.toString("{value}", String.valueOf(min))).append(" ");
        }
        result.append(Lang.HUMAN_TIME_SECOND.toString("{value}", String.valueOf(sec)));
        return result.toString();
    }

    public static String getString(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }


    public static String strip(String str) {
        return stripAllColors(str);
    }

    public static String stripAllColors(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return MiniMessageUtils.toPlainText(text);
    }

    public static List<String> tabCompletePrepare(String[] args, Stream<String> stream) {
        return StringUtils.tabCompletePrepare(args, 1, stream);
    }

    // Cap suggestions so huge song libraries don't build/serialize thousands of completions
    // per keystroke on the main thread.
    private static final int MAX_TAB_COMPLETIONS = 200;

    @NotNull
    public static List<String> tabCompletePrepare(String[] args, int position, Stream<String> stream) {
        if (args.length < position) {
            return stream.limit(MAX_TAB_COMPLETIONS).collect(Collectors.toList());
        }
        if (args.length == position) {
            String start = args[position - 1].toLowerCase();
            return stream.filter(s -> s.toLowerCase().startsWith(start)).limit(MAX_TAB_COMPLETIONS).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private StringUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
