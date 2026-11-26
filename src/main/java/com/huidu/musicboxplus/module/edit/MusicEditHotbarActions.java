package com.huidu.musicboxplus.module.edit;

import java.util.Map;

/**
 * Which toolbar button a layout character means in the editor.
 *
 * Both the renderer and the click handler used to carry their own chain of nineteen
 * c == mapping.getOrDefault("play", 'P') comparisons. Two copies of the same table drift:
 * adding a button to one and forgetting the other gives a button that renders but does nothing, and
 * the chains were long enough that the duplication was easy to miss.
 *
 * Resolution mirrors the old if-else order: the first action in declaration order whose character
 * matches wins, so a layout that maps two actions to one character behaves exactly as before instead
 * of silently changing which one is reachable.
 */
final class MusicEditHotbarActions {

    /** Toolbar actions, in the order the old if-else chains tested them. */
    enum Action {
        PITCH_UP("pitch-up", 'U'),
        PITCH_DOWN("pitch-down", 'D'),
        TICK_LEFT("tick-left", 'L'),
        TICK_RIGHT("tick-right", 'R'),
        INSTRUMENT("instrument", 'I'),
        PLAY("play", 'P'),
        SAVE("save", 'S'),
        EXIT("exit", 'H'),
        BPM_UP("bpm-up", 'B'),
        BPM_DOWN("bpm-down", 'T'),
        TIME_SIGNATURE("time-signature", 'M'),
        SETTINGS("settings", 'G'),
        CLEAR_ALL("clear-all", 'C'),
        UNDO("undo", 'Y'),
        REDO("redo", 'Z'),
        COPY("copy", 'Q'),
        PASTE("paste", 'J'),
        // The click handler tested batch-instrument before delete-selection while the renderer did
        // the opposite. The click order wins here because that is what a player experiences; the two
        // only differ for a config that maps both keys to one character.
        BATCH_INSTRUMENT("batch-instrument", 'W'),
        DELETE_SELECTION("delete-selection", 'K');

        private final String configKey;
        private final char defaultChar;

        Action(String configKey, char defaultChar) {
            this.configKey = configKey;
            this.defaultChar = defaultChar;
        }

        /** Key in gui-config.yml's button-mapping section. */
        String configKey() {
            return configKey;
        }

        char defaultChar() {
            return defaultChar;
        }
    }

    /** Returned for a layout character that is not a toolbar button. */
    static final Action NONE = null;

    private final Map<Character, Action> byChar = new java.util.HashMap<>();

    private MusicEditHotbarActions() {
    }

    static MusicEditHotbarActions create(Map<String, Character> buttonMapping) {
        MusicEditHotbarActions actions = new MusicEditHotbarActions();
        for (Action action : Action.values()) {
            Character configured = buttonMapping == null ? null : buttonMapping.get(action.configKey());
            char c = configured == null ? action.defaultChar() : configured;
            // putIfAbsent, not put: the first action in declaration order keeps a shared character,
            // which is what the sequential if-else chain did.
            actions.byChar.putIfAbsent(c, action);
        }
        return actions;
    }

    /** The action for a layout character, or null when the character is not a button. */
    Action actionFor(char c) {
        return byChar.get(c);
    }

    /** Characters that more than one action claims, for a startup warning; empty in a sane config. */
    java.util.List<Character> duplicateChars(Map<String, Character> buttonMapping) {
        Map<Character, Integer> counts = new java.util.HashMap<>();
        for (Action action : Action.values()) {
            Character configured = buttonMapping == null ? null : buttonMapping.get(action.configKey());
            char c = configured == null ? action.defaultChar() : configured;
            counts.merge(c, 1, Integer::sum);
        }
        java.util.List<Character> duplicates = new java.util.ArrayList<>();
        counts.forEach((c, count) -> {
            if (count > 1) {
                duplicates.add(c);
            }
        });
        duplicates.sort(Character::compare);
        return duplicates;
    }
}
