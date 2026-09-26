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

    // Whether every character fits in the one-byte-per-character space a .nbs header string uses.
    // Anything read by NbsReader qualifies by construction; a file or folder name taken from the
    // operating system usually does not, which is what makes it a better name than the header.
    public static boolean fitsSingleByteString(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                return false;
            }
        }
        return true;
    }

    // Whether text read out of a .nbs header names nothing at all: empty, or nothing but '?'. That
    // is what every character above U+00FF used to become when this plugin wrote a title, and what
    // other tools still produce for characters their encoding cannot hold.
    public static boolean isMeaninglessHeaderText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '?' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    // The name to show for a song that came out of a .nbs file.
    //
    // A .nbs header string is one byte per character -- NoteBlockAPI reads a title as
    // `(char) byte`, and so does NbsReader -- so a CJK title cannot be stored in the file at all.
    // When the header names nothing (empty or all '?'), or when it lives entirely inside that
    // single-byte space while the file name does not, the file name is the name the song is meant
    // to be known by: either a tool mangled the title on the way in, or the author titled the song
    // in Latin and named the file in their own language. The file name is also the one thing about
    // a song that no .nbs tool can mangle.
    //
    // A Latin-1 title like "Café" is inside the single-byte space only if the file name is too, so
    // those keep winning -- which is what the corpus and the Western case rely on.
    public static String songNameFromHeader(String headerText, String fileName) {
        String fallback = fileName == null ? "" : fileName;
        if (isMeaninglessHeaderText(headerText)) {
            return fallback;
        }
        if (fitsSingleByteString(headerText) && !fitsSingleByteString(fallback)) {
            return fallback;
        }
        return headerText;
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
