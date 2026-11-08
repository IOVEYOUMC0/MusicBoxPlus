package com.huidu.musicboxplus.module.edit.io;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Naming rules are tested here without building a PlayerMusic: ordinary construction reads the
// plugin configuration through MusicBox, and the converter reaches MusicNote's instrument enum,
// which initializes Bukkit Instrument/Material. The pure helpers are the server-free boundary.
class MusicExportServiceTest {

    @TempDir
    Path directory;

    @Test
    void sanitizeReplacesIllegalAndControlCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i_j",
                MusicExportService.sanitize("a<b>c:d\"e/f\\g|h?i*j"));
        assertEquals("a_b_c", MusicExportService.sanitize("a\tb\nc"));
        assertEquals("a_b", MusicExportService.sanitize("a\0b"));
        assertEquals("a_b", MusicExportService.sanitize("a\u007fb"));
    }

    @Test
    void sanitizeCollapsesConsecutiveUnderscores() {
        assertEquals("a_b", MusicExportService.sanitize("a___b"));
        // An illegal character next to an existing underscore still produces one separator.
        assertEquals("a_b", MusicExportService.sanitize("a_?_b"));
    }

    @Test
    void sanitizeStripsLeadingAndTrailingSpacesAndDots() {
        assertEquals("My Song", MusicExportService.sanitize("  ..My Song..  "));
        assertEquals("My.Song", MusicExportService.sanitize("My.Song"));
    }

    @Test
    void sanitizeFallsBackToSongForEmptyNames() {
        assertEquals("song", MusicExportService.sanitize(null));
        assertEquals("song", MusicExportService.sanitize(""));
        assertEquals("song", MusicExportService.sanitize("   "));
        assertEquals("song", MusicExportService.sanitize("..."));
        assertEquals("song", MusicExportService.sanitize(" . . "));
    }

    @Test
    void sanitizeTruncatesLongNamesToEightyCharacters() {
        String sanitized = MusicExportService.sanitize("a".repeat(120));

        assertEquals("a".repeat(80), sanitized);
        assertEquals(80, sanitized.length());
    }

    @Test
    void uniqueTargetAppendsSuffixOnRepeatedNames() {
        Set<String> used = new HashSet<>();

        assertEquals(directory.resolve("song.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "song", used));
        assertEquals(directory.resolve("song-2.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "song", used));
        assertEquals(directory.resolve("song-3.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "song", used));
    }

    @Test
    void uniqueTargetTreatsNamesDifferingOnlyInCaseAsTheSameName() {
        Set<String> used = new HashSet<>();

        assertEquals(directory.resolve("Song.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "Song", used));
        assertEquals(directory.resolve("song-2.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "song", used));
    }

    @Test
    void uniqueTargetRecognisesAnAlreadyUsedNameInAnyCase() {
        Set<String> used = new HashSet<>();
        // A caller can seed the set from files already on disk, where the original case survives.
        used.add("Song.nbs");

        assertEquals(directory.resolve("song-2.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "song", used));
    }

    @Test
    void uniqueTargetFallsBackToSongForBlankBase() {
        Set<String> used = new HashSet<>();

        assertEquals(directory.resolve("song.nbs"),
                MusicExportService.uniqueTarget(directory.toFile(), "   ", used));
    }
}
