package com.huidu.musicboxplus.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

// Pins the behaviour of MiniMessageUtils#convertToMiniMessage(String): the code-to-tag
// mappings plus the edge cases of the single-pass scan (no allocation for code-free input,
// both & and § as prefixes, dangling prefix at end of input, case-insensitive codes).
class MiniMessageUtilsConvertTest {

    @Test
    void nullAndEmptyReturnEmptyString() {
        assertEquals("", MiniMessageUtils.convertToMiniMessage(null));
        assertEquals("", MiniMessageUtils.convertToMiniMessage(""));
    }

    @Test
    void plainTextIsReturnedUnchanged() {
        String input = "hello world";
        String result = MiniMessageUtils.convertToMiniMessage(input);
        assertEquals(input, result);
        // Contract: code-free input hands back the SAME instance, so no StringBuilder is
        // allocated on the hot path.
        assertSame(input, result, "no-code input should not allocate");
    }

    @Test
    void everyLowerCaseCodeMapsToCorrectTag() {
        assertEquals("<black>", MiniMessageUtils.convertToMiniMessage("&0"));
        assertEquals("<dark_blue>", MiniMessageUtils.convertToMiniMessage("&1"));
        assertEquals("<dark_green>", MiniMessageUtils.convertToMiniMessage("&2"));
        assertEquals("<dark_aqua>", MiniMessageUtils.convertToMiniMessage("&3"));
        assertEquals("<dark_red>", MiniMessageUtils.convertToMiniMessage("&4"));
        assertEquals("<dark_purple>", MiniMessageUtils.convertToMiniMessage("&5"));
        assertEquals("<gold>", MiniMessageUtils.convertToMiniMessage("&6"));
        assertEquals("<gray>", MiniMessageUtils.convertToMiniMessage("&7"));
        assertEquals("<dark_gray>", MiniMessageUtils.convertToMiniMessage("&8"));
        assertEquals("<blue>", MiniMessageUtils.convertToMiniMessage("&9"));
        assertEquals("<green>", MiniMessageUtils.convertToMiniMessage("&a"));
        assertEquals("<aqua>", MiniMessageUtils.convertToMiniMessage("&b"));
        assertEquals("<red>", MiniMessageUtils.convertToMiniMessage("&c"));
        assertEquals("<light_purple>", MiniMessageUtils.convertToMiniMessage("&d"));
        assertEquals("<yellow>", MiniMessageUtils.convertToMiniMessage("&e"));
        assertEquals("<white>", MiniMessageUtils.convertToMiniMessage("&f"));
        assertEquals("<obfuscated>", MiniMessageUtils.convertToMiniMessage("&k"));
        assertEquals("<bold>", MiniMessageUtils.convertToMiniMessage("&l"));
        assertEquals("<strikethrough>", MiniMessageUtils.convertToMiniMessage("&m"));
        assertEquals("<underlined>", MiniMessageUtils.convertToMiniMessage("&n"));
        assertEquals("<italic>", MiniMessageUtils.convertToMiniMessage("&o"));
        assertEquals("<reset>", MiniMessageUtils.convertToMiniMessage("&r"));
    }

    @Test
    void uppercaseCodesAreMappedCaseInsensitively() {
        assertEquals("<red>", MiniMessageUtils.convertToMiniMessage("&C"));
        assertEquals("<bold>", MiniMessageUtils.convertToMiniMessage("&L"));
        assertEquals("<reset>", MiniMessageUtils.convertToMiniMessage("&R"));
    }

    @Test
    void sectionCharIsTreatedAsLegacyPrefixToo() {
        assertEquals("<red>hello", MiniMessageUtils.convertToMiniMessage("§chello"));
        assertEquals("<bold>WORLD", MiniMessageUtils.convertToMiniMessage("§lWORLD"));
    }

    @Test
    void mixedPrefixesInSameStringBothWork() {
        assertEquals("<red>foo<bold>bar",
                MiniMessageUtils.convertToMiniMessage("&cfoo§lbar"));
    }

    @Test
    void surroundingTextIsPreserved() {
        assertEquals("hi <red>there<reset>!",
                MiniMessageUtils.convertToMiniMessage("hi &cthere&r!"));
    }

    @Test
    void unknownCodesAfterAmpersandArePreservedLiterally() {
        assertEquals("a&z b& c", MiniMessageUtils.convertToMiniMessage("a&z b& c"));
        assertEquals("a&!b", MiniMessageUtils.convertToMiniMessage("a&!b"));
    }

    @Test
    void danglingAmpersandAtEndIsPreserved() {
        // A prefix in the last position must not make the scanner read past the end of input.
        assertEquals("hello&", MiniMessageUtils.convertToMiniMessage("hello&"));
    }

    @Test
    void doublePrefixGreedilyMatchesInnerCode() {
        // The scan is left-to-right and greedy: at index 0 the '&' is followed by another '&',
        // which is not a code, so it stays literal; the '&' at index 1 is followed by 'c' and
        // converts. Net result: a literal '&' plus "<red>".
        assertEquals("&<red>", MiniMessageUtils.convertToMiniMessage("&&c"));
        assertEquals("<red><bold>", MiniMessageUtils.convertToMiniMessage("&c&l"));
    }

    @Test
    void multipleCodesInSequenceAllConvert() {
        assertEquals("<red><bold><underlined>warning",
                MiniMessageUtils.convertToMiniMessage("&c&l&nwarning"));
    }

    @Test
    void noCodesMeansNoAllocation() {
        String input = "The quick brown fox jumps over the lazy dog 1234567890";
        assertSame(input, MiniMessageUtils.convertToMiniMessage(input));
    }
}
