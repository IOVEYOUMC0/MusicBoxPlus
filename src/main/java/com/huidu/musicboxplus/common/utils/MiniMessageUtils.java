package com.huidu.musicboxplus.common.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.*;

public final class MiniMessageUtils {
    private static final char SECTION_CHAR = '\u00A7';
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    // Bounded LRU memoization of processComponent results. Turning a string into a Component
    // (legacy detection, MiniMessage deserialize) is costly and is re-run for the very same
    // strings constantly: static config button names/lore are rebuilt on every GUI render.
    // Components are immutable, so a cached instance is safe to share; the LRU cap keeps the
    // finite set of static strings hot while bounding churn from dynamic ones.
    private static final int COMPONENT_CACHE_MAX = 1024;
    private static final Map<String, Component> COMPONENT_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
                    return size() > COMPONENT_CACHE_MAX;
                }
            });

    // A second, separate (and much smaller) LRU for strings that are almost never repeated: the
    // control-panel progress bar rebuilds one name and a few lore lines per slot from the current
    // tick, so it produces tens of fresh strings every second per viewer. Those went into the cache
    // above, one miss each, and evicted the whole 1024-entry set of static GUI strings within a
    // minute -- precisely the memoization that cap exists to keep. Separate maps mean dynamic churn
    // can only evict dynamic churn; the dynamic side still dedupes the one case that does repeat,
    // two viewers looking at the same song at the same tick.
    private static final int DYNAMIC_CACHE_MAX = 128;
    private static final Map<String, Component> DYNAMIC_COMPONENT_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
                    return size() > DYNAMIC_CACHE_MAX;
                }
            });

    private MiniMessageUtils() {}

    public static Component parseMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(input);
        } catch (Exception e) {
            return Component.text(input);
        }
    }

    public static String toLegacyText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return toLegacyText(processComponent(input));
    }

    public static String toLegacyText(Component component) {
        if (component == null) {
            return "";
        }
        return LEGACY_SECTION.serialize(component);
    }

    public static boolean containsMiniMessageTags(String input) {
        return input != null && input.contains("<") && input.contains(">");
    }

    // Makes a string safe to place inside a MiniMessage template: `<` becomes `\<`, so the text can
    // only ever render as itself.
    //
    // This is for values that come from somewhere the plugin does not control -- a song title read
    // out of a .nbs header, a file name, a playlist name, chat input. Substituting one of those into
    // a language template and then parsing the result let the data be markup: a song called
    // `<click:run_command:/op me>` was a component, not a title.
    //
    // Deliberately narrow, and the narrowness matters. It escapes MiniMessage's own delimiters and
    // nothing else, so legacy `&` and section codes in a value still work -- which is load-bearing,
    // because the intentional-markup call sites all pass Lang.X.toString(), and that serialises to
    // legacy codes rather than to MiniMessage. Widening this to strip `&` as well would turn every
    // one of those into literal text.
    //
    // What it does not do is stop a value from *restyling* the message via `&`/`§` codes: those can
    // only change colour and formatting, never introduce a component, so it is a cosmetic residual
    // rather than an injection one.
    public static String escapeTags(String input) {
        if (input == null || input.isEmpty() || input.indexOf('<') < 0) {
            return input;
        }
        return MINI_MESSAGE.escapeTags(input);
    }

    public static String processText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return toLegacyText(processComponent(input));
    }

    public static String toPlainText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return toPlainText(processComponent(input));
    }

    public static String toPlainText(Component component) {
        if (component == null) {
            return "";
        }
        return PLAIN_TEXT.serialize(component);
    }

    public static Component processComponent(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        Component cached = COMPONENT_CACHE.get(input);
        if (cached != null) {
            return cached;
        }
        Component result = computeComponent(input);
        COMPONENT_CACHE.put(input, result);
        return result;
    }

    /**
     * Same as {@link #processComponent}, but memoized in the small dynamic cache.
     *
     * For callers whose strings carry a value that changes on every render (a tick number, a
     * countdown, a percentage): they never hit the shared cache, so putting them there only evicts
     * the static strings it is sized for. See {@link #DYNAMIC_COMPONENT_CACHE}.
     */
    public static Component processDynamicComponent(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        Component cached = DYNAMIC_COMPONENT_CACHE.get(input);
        if (cached != null) {
            return cached;
        }
        Component result = computeComponent(input);
        DYNAMIC_COMPONENT_CACHE.put(input, result);
        return result;
    }

    private static Component computeComponent(String input) {
        String normalized = input;
        if (normalized.indexOf('&') >= 0 || normalized.indexOf(SECTION_CHAR) >= 0) {
            normalized = convertToMiniMessage(normalized);
        }
        if (containsMiniMessageTags(normalized)) {
            Component parsed = parseMiniMessage(normalized);
            return removeDefaultItalic(parsed);
        }
        if (containsMiniMessageTags(input)) {
            Component parsed = parseMiniMessage(input);
            return removeDefaultItalic(parsed);
        }
        try {
            if (input.indexOf(SECTION_CHAR) >= 0) {
                return removeDefaultItalic(LEGACY_SECTION.deserialize(input));
            }
            return removeDefaultItalic(LEGACY_AMPERSAND.deserialize(input));
        } catch (Exception e) {
            return removeDefaultItalic(Component.text(input));
        }
    }
    
    private static Component removeDefaultItalic(Component component) {
        if (component == null) {
            return Component.empty();
        }
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> processComponents(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        // Explicit loop avoids the Stream/Spliterator/Collector allocations that would dwarf
        // the actual work for the typical 1-10 line lore, rebuilt on every GUI render.
        List<Component> result = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            result.add(processComponent(input));
        }
        return result;
    }

    /** {@link #processDynamicComponent} for a lore block. */
    public static List<Component> processDynamicComponents(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Component> result = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            result.add(processDynamicComponent(input));
        }
        return result;
    }

    public static String getInventoryTitle(Component component) {
        return toLegacyText(component);
    }

    // Legacy code -> MiniMessage tag, indexed by the lowercased code char; null cell = not a code.
    // Lets convertToMiniMessage do a single O(n) scan instead of one full-string replace pass
    // per code (22 codes x upper/lower).
    private static final String[] CODE_TO_TAG = new String[128];
    static {
        CODE_TO_TAG['0'] = "<black>";
        CODE_TO_TAG['1'] = "<dark_blue>";
        CODE_TO_TAG['2'] = "<dark_green>";
        CODE_TO_TAG['3'] = "<dark_aqua>";
        CODE_TO_TAG['4'] = "<dark_red>";
        CODE_TO_TAG['5'] = "<dark_purple>";
        CODE_TO_TAG['6'] = "<gold>";
        CODE_TO_TAG['7'] = "<gray>";
        CODE_TO_TAG['8'] = "<dark_gray>";
        CODE_TO_TAG['9'] = "<blue>";
        CODE_TO_TAG['a'] = "<green>";
        CODE_TO_TAG['b'] = "<aqua>";
        CODE_TO_TAG['c'] = "<red>";
        CODE_TO_TAG['d'] = "<light_purple>";
        CODE_TO_TAG['e'] = "<yellow>";
        CODE_TO_TAG['f'] = "<white>";
        CODE_TO_TAG['k'] = "<obfuscated>";
        CODE_TO_TAG['l'] = "<bold>";
        CODE_TO_TAG['m'] = "<strikethrough>";
        CODE_TO_TAG['n'] = "<underlined>";
        CODE_TO_TAG['o'] = "<italic>";
        CODE_TO_TAG['r'] = "<reset>";
    }

    public static String convertToMiniMessage(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return "";
        }
        StringBuilder sb = null;
        int n = legacyText.length();
        for (int i = 0; i < n; i++) {
            char c = legacyText.charAt(i);
            if ((c == '&' || c == SECTION_CHAR) && i + 1 < n) {
                char code = legacyText.charAt(i + 1);
                char lower = (code >= 'A' && code <= 'Z') ? (char) (code + 32) : code;
                String tag = (lower < CODE_TO_TAG.length) ? CODE_TO_TAG[lower] : null;
                if (tag != null) {
                    if (sb == null) {
                        sb = new StringBuilder(n + 16);
                        sb.append(legacyText, 0, i);
                    }
                    sb.append(tag);
                    i++; // skip the code char
                    continue;
                }
            }
            if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? legacyText : sb.toString();
    }
}
