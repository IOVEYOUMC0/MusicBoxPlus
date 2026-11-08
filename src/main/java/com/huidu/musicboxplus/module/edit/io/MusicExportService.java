package com.huidu.musicboxplus.module.edit.io;

import com.huidu.musicboxplus.core.nbs.NbsWriter;
import com.huidu.musicboxplus.module.edit.PlayerMusic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Batch export of player-made songs to .nbs files in a caller-chosen directory.
//
// A batch must not lose its healthy songs because one of them cannot be converted or written:
// "export all" is exactly the action where a single out-of-range note must not abort the loop and
// leave every later song unwritten. Each song is therefore isolated, and anything that went wrong
// is reported through ExportResult.failures instead of an exception.
//
// The naming policy lives in package-private static helpers with no Bukkit dependencies. File
// naming is where the platform differences are, so it is the part that needs unit tests that can
// run without a server.
public final class MusicExportService {

    private static final String EXTENSION = ".nbs";
    private static final String FALLBACK_NAME = "song";

    // Every filesystem this plugin targets allows a 255-character component; 80 leaves room for
    // the "-<n>" suffix while staying far below that limit and below the old path-length cap.
    private static final int MAX_BASE_LENGTH = 80;

    // Forbidden on Windows, and '/' is the one separator Unix reserves. Rejecting the Windows set
    // everywhere keeps an export made on one platform usable on another.
    private static final String ILLEGAL_CHARS = "<>:\"/\\|?*";

    private MusicExportService() {
    }

    public static ExportResult export(Collection<PlayerMusic> songs, File targetDir) {
        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir must not be null");
        }

        List<Path> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            Files.createDirectories(targetDir.toPath());
        } catch (IOException | RuntimeException failure) {
            failures.add("export directory '" + targetDir + "': " + failure.getMessage());
            return new ExportResult(targetDir, files, warnings, failures);
        }

        // Only names handed out in this batch are reserved. A repeated export over the same
        // directory intentionally replaces its own previous files (NbsWriter writes atomically),
        // rather than accumulating "song-2", "song-3", ... on every run.
        Set<String> used = new HashSet<>();
        // A null collection means nothing was selected to export; still create and report the
        // directory, so a caller that built the selection from an optional filter needs no special
        // case before calling this.
        if (songs != null) {
            for (PlayerMusic song : songs) {
                exportOne(song, targetDir, used, files, warnings, failures);
            }
        }
        return new ExportResult(targetDir, files, warnings, failures);
    }

    private static void exportOne(PlayerMusic song, File directory, Set<String> used,
                                  List<Path> files, List<String> warnings, List<String> failures) {
        if (song == null) {
            failures.add("[" + FALLBACK_NAME + "] null song");
            return;
        }
        // Also inside the try: a malformed song must not take the rest of the batch down even if
        // reading its metadata is what fails.
        String displayName = FALLBACK_NAME;
        try {
            String songName = song.getName();
            if (songName != null && !songName.isBlank()) {
                displayName = songName;
            }
            NoteBlockSongConverter.ConversionResult conversion =
                    NoteBlockSongConverter.fromPlayerMusic(song);
            // A batch can contain hundreds of songs, so a bare warning code would not say which
            // file it belongs to. Prefix with the name the player knows, not the sanitized on-disk
            // name, which may have lost characters.
            for (String warning : conversion.warnings()) {
                warnings.add("[" + displayName + "] " + warning);
            }
            Path target = uniqueTarget(directory, sanitize(songName), used);
            NbsWriter.write(conversion.song(), target);
            files.add(target);
        } catch (IOException | RuntimeException failure) {
            // Also catches unexpected conversion failures. One malformed song must not stop the
            // rest of the batch, and the caller gets the reason in failures.
            failures.add("[" + displayName + "] " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    // Windows cannot represent these characters in a file name, and leading/trailing spaces and
    // dots are silently dropped or rejected by Explorer and by the Win32 API. The result must also
    // remain a single path component: '/' and '\\' would otherwise let a song name escape targetDir.
    static String sanitize(String name) {
        String value = name == null ? "" : name;
        StringBuilder cleaned = new StringBuilder(value.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char mapped = ILLEGAL_CHARS.indexOf(c) >= 0 || Character.isISOControl(c) ? '_' : c;
            if (mapped == '_') {
                // Collapse runs so "a??b" does not become "a__b", and an illegal character next
                // to an existing underscore does not add a second one.
                if (!previousUnderscore) {
                    cleaned.append(mapped);
                }
                previousUnderscore = true;
            } else {
                cleaned.append(mapped);
                previousUnderscore = false;
            }
        }

        String trimmed = stripSpacesAndDots(cleaned.toString());
        if (trimmed.isEmpty()) {
            return FALLBACK_NAME;
        }
        // Truncation can expose a dot or space that was safely inside the original name, so clean
        // both edges once more before the value becomes a path component.
        String truncated = stripSpacesAndDots(truncate(trimmed));
        return truncated.isEmpty() ? FALLBACK_NAME : truncated;
    }

    private static String stripSpacesAndDots(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdgeTrimmable(value.charAt(start))) {
            start++;
        }
        while (end > start && isEdgeTrimmable(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isEdgeTrimmable(char c) {
        // All Unicode whitespace counts as a space here, not just U+0020, because names are often
        // pasted from chat or a web page.
        return c == '.' || Character.isWhitespace(c);
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_BASE_LENGTH) {
            return value;
        }
        int end = MAX_BASE_LENGTH;
        // Do not split a surrogate pair: a lone surrogate is not a legal path character on
        // Windows and would make the write fail for a name that is otherwise fine.
        if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    // Reserves and returns the next free path for base. Keys added here are lower-cased because
    // Windows treats "Song.nbs" and "song.nbs" as the same file. A set the caller seeded in mixed
    // case is still compared without case, so entries taken from disk behave the same way.
    //
    // The caller passes an already sanitized base. The fallback keeps this helper safe when called
    // from a test or a future caller, and never lets a blank stem become ".nbs".
    static Path uniqueTarget(File directory, String base, Set<String> used) {
        String stem = base == null || base.isBlank() ? FALLBACK_NAME : base;
        String fileName = stem + EXTENSION;
        int suffix = 2;
        while (isUsed(used, fileName)) {
            fileName = stem + "-" + suffix + EXTENSION;
            suffix++;
        }
        used.add(fileName.toLowerCase(Locale.ROOT));
        return directory.toPath().resolve(fileName);
    }

    private static boolean isUsed(Set<String> used, String fileName) {
        if (used.contains(fileName.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // A caller may seed the set with names taken straight from disk, so those entries are not
        // guaranteed to be lower-cased. Windows would still consider them the same file.
        for (String existing : used) {
            if (existing != null && existing.equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    // Lists are copied defensively: a record should be a stable report once returned, even if a
    // caller keeps appending to the lists it passed in.
    public record ExportResult(File directory, List<Path> files, List<String> warnings, List<String> failures) {

        public ExportResult {
            files = List.copyOf(files);
            warnings = List.copyOf(warnings);
            failures = List.copyOf(failures);
        }
    }
}
