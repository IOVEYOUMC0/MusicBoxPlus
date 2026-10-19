package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Language-template substitution: the values that go into a message must not be able to become the
// message's markup.
//
// Every one of these values comes from somewhere the plugin does not control -- a song title read out
// of a .nbs header, a file name, a playlist name, chat input -- and the substituted result is then
// parsed as MiniMessage, so before this a song called `<click:run_command:/op me>` was a clickable
// component rather than a title.
class LanguageSubstituteTest {

    @Test
    void aValueCannotIntroduceMarkup() {
        String resolved = LanguageConfig.substitute("Song: {song}", "{song}", "<red>Hostile</red>");

        // Escaped, so it renders as the text the user typed...
        assertTrue(resolved.contains("\\<red>"), "the tag should have been escaped: " + resolved);
        // ...and MiniMessage agrees it is text, not a component: the rendered form keeps the brackets.
        assertEquals("Song: <red>Hostile</red>",
                MiniMessageUtils.toPlainText(resolved),
                "an escaped value must render literally, got: " + resolved);
    }

    // The one that makes this a security fix rather than cosmetics: a click/hover tag is a component
    // with behaviour, not just styling.
    @Test
    void aValueCannotIntroduceAClickableComponent() {
        String resolved = LanguageConfig.substitute("{name}", "{name}",
                "<click:run_command:/op attacker>click me</click>");

        net.kyori.adventure.text.Component parsed = MiniMessageUtils.processComponent(resolved);
        assertEquals("<click:run_command:/op attacker>click me</click>",
                MiniMessageUtils.toPlainText(parsed),
                "the click tag must survive as literal text");
        assertNoClickEvent(parsed, "the parsed value must carry no click event");
    }

    private static void assertNoClickEvent(net.kyori.adventure.text.Component component, String message) {
        assertNull(component.clickEvent(), message);
        for (net.kyori.adventure.text.Component child : component.children()) {
            assertNoClickEvent(child, message);
        }
    }

    @Test
    void placeholdersAreNotRescannedAfterSubstitution() {
        // A song literally named "{mode}" used to be substituted a second time by the following
        // replace(): the sequential loop rescanned the text it had already inserted.
        String resolved = LanguageConfig.substitute(
                "Playing {song} in {mode} mode", "{song}", "{mode}", "{mode}", "single");

        assertEquals("Playing {mode} in single mode", resolved,
                "an inserted value must not be treated as a placeholder");
    }

    @Test
    void longestPlaceholderWins() {
        // Java alternation takes the first matching branch, not the longest, so unsorted
        // placeholders let "{song}" shadow "{song_name}".
        String resolved = LanguageConfig.substitute("A {song_name} B {song}",
                "{song}", "short", "{song_name}", "long");

        assertEquals("A long B short", resolved);
    }

    @Test
    void intentionalMarkupStillWorks() {
        // The call sites that pass a rendered language value as a value all use Lang.X.toString(),
        // which serialises to legacy section codes (processText -> toLegacyText), not to MiniMessage.
        // Escaping MiniMessage's delimiters and nothing else is what keeps those working, so this
        // must not regress into literal text.
        //
        // Deliberately no surrounding tag in the template: a value ending in a reset code inside
        // "<x>...</x>" orphans the closing tag and MiniMessage then renders it literally. That is a
        // pre-existing property of mixing the two encodings, unrelated to escaping, and none of the
        // current call sites hit it because the values they pass (Container has no songs, Payment
        // failed) carry no codes at all.
        String resolved = LanguageConfig.substitute("Error: {message}", "{message}", "\u00A7cNo songs");

        assertEquals("Error: No songs", MiniMessageUtils.toPlainText(resolved),
                "a legacy-coded value must still be interpreted, got: " + resolved);
        assertHasColour(MiniMessageUtils.processComponent(resolved),
                "the section code in the value should still colour it");
    }

    private static void assertHasColour(net.kyori.adventure.text.Component component, String message) {
        if (component.color() != null) {
            return;
        }
        for (net.kyori.adventure.text.Component child : component.children()) {
            if (child.color() != null) {
                return;
            }
        }
        throw new AssertionError(message);
    }

    @Test
    void oddOrEmptyReplacementListsAreLeftAlone() {
        assertEquals("a {x}", LanguageConfig.substitute("a {x}", "{x}"));
        assertEquals("a {x}", LanguageConfig.substitute("a {x}", new String[0]));
        // A null value leaves the placeholder in place, as the previous loop did.
        assertEquals("a {x}", LanguageConfig.substitute("a {x}", "{x}", null));
        assertEquals("a ", LanguageConfig.substitute("a {x}", "{x}", ""));
    }
}
