package com.huidu.musicboxplus.common.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FileUtils {
    public static String getFilename(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int index = fileName.lastIndexOf(46);
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    public static List<String> readFileToList(File file) throws IOException {
        // Try common encodings in order so a non-UTF-8 file (e.g. info.txt saved as GBK/ANSI
        // by Notepad on Chinese Windows) still loads instead of throwing MalformedInputException.
        List<Charset> attempts = new ArrayList<>();
        attempts.add(StandardCharsets.UTF_8);
        try {
            attempts.add(Charset.forName("GBK"));
        } catch (Exception ignored) {
            // GBK not available on this JVM; skip it.
        }
        attempts.add(Charset.defaultCharset());
        for (Charset charset : attempts) {
            try {
                return Files.readAllLines(file.toPath(), charset);
            } catch (MalformedInputException | UnmappableCharacterException ignored) {
                // Wrong charset guess; try the next one.
            }
        }
        // Last resort: decode as UTF-8 replacing any invalid bytes (never throws on bad bytes).
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return Arrays.asList(content.split("\r?\n", -1));
    }

    private FileUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}

