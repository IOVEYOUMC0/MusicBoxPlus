package com.huidu.musicboxplus.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// How a song read from a .nbs gets its name.
//
// The format stores one byte per character, so a Chinese title physically cannot be in the file.
// These cases pin down which source wins, because the wrong rule either shows '?'/mojibake to the
// player or silently ignores a deliberate Latin title.
class StringUtilsSongNameTest {

    @Test
    void fallsBackToTheFileNameWhenTheHeaderNamesNothing() {
        assertEquals("歌唱祖国", StringUtils.songNameFromHeader("", "歌唱祖国"));
        assertEquals("歌唱祖国", StringUtils.songNameFromHeader(null, "歌唱祖国"));
        assertEquals("Faded", StringUtils.songNameFromHeader("???", "Faded"));
        assertEquals("Faded", StringUtils.songNameFromHeader("   ", "Faded"));
    }

    @Test
    void keepsATitleTheHeaderCanActuallyHold() {
        assertEquals("Faded", StringUtils.songNameFromHeader("Faded", "Faded"));
        // Latin-1 is inside the single-byte space, so a real accented title still wins.
        assertEquals("Café", StringUtils.songNameFromHeader("Café", "something_else"));
        assertEquals("Ørn", StringUtils.songNameFromHeader("Ørn", "orn"));
    }

    @Test
    void prefersTheFileNameWhenTheHeaderCannotBeCarryingIt() {
        // The mangled title produced by every writer that maps CJK to '?' or to low bytes.
        assertEquals("忘情牛肉面", StringUtils.songNameFromHeader("?????", "忘情牛肉面"));
        assertEquals("忘情牛肉面", StringUtils.songNameFromHeader("ØÅ[\u0089b", "忘情牛肉面"));
        // A Latin title on a file the owner named in Chinese: the header cannot hold what the file
        // name holds, so the file name is the name the song is meant to be known by.
        assertEquals("歌曲", StringUtils.songNameFromHeader("Song", "歌曲"));
    }

    @Test
    void handlesAnEmptyFileName() {
        assertEquals("", StringUtils.songNameFromHeader("", ""));
        assertEquals("", StringUtils.songNameFromHeader(null, null));
        assertEquals("Song", StringUtils.songNameFromHeader("Song", ""));
    }

    @Test
    void classifiesTextByWhatTheHeaderCanStore() {
        assertTrue(StringUtils.fitsSingleByteString("Café"));
        assertTrue(StringUtils.fitsSingleByteString(null));
        assertFalse(StringUtils.fitsSingleByteString("歌曲"));
        assertFalse(StringUtils.fitsSingleByteString("Faded \uD83C\uDFB5"));

        assertTrue(StringUtils.isMeaninglessHeaderText(""));
        assertTrue(StringUtils.isMeaninglessHeaderText(" ? "));
        assertFalse(StringUtils.isMeaninglessHeaderText("?x?"));
    }
}
