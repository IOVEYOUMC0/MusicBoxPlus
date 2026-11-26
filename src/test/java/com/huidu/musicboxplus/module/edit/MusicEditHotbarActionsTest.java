package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// The editor toolbar table. Both the renderer and the click handler read it now, so these tests are
// what keeps "renders but does nothing" from coming back.
class MusicEditHotbarActionsTest {

    @Test
    void defaultCharactersResolveToTheirActions() {
        MusicEditHotbarActions actions = MusicEditHotbarActions.create(Map.of());

        assertEquals(MusicEditHotbarActions.Action.PLAY, actions.actionFor('P'));
        assertEquals(MusicEditHotbarActions.Action.SAVE, actions.actionFor('S'));
        assertEquals(MusicEditHotbarActions.Action.UNDO, actions.actionFor('Y'));
        assertEquals(MusicEditHotbarActions.Action.BATCH_INSTRUMENT, actions.actionFor('W'));
    }

    @Test
    void aConfiguredCharacterOverridesTheDefault() {
        MusicEditHotbarActions actions = MusicEditHotbarActions.create(Map.of("play", '1'));

        assertEquals(MusicEditHotbarActions.Action.PLAY, actions.actionFor('1'));
        assertNull(actions.actionFor('P'), "the overridden default must stop being a button");
    }

    @Test
    void aCharacterSharedByTwoActionsKeepsTheFirstOne() {
        // The old if-else chain tested pitch-up before pitch-down, so the shared character opened
        // pitch-up. Changing that silently would swap two buttons.
        Map<String, Character> mapping = new HashMap<>();
        mapping.put("pitch-up", 'X');
        mapping.put("pitch-down", 'X');

        MusicEditHotbarActions actions = MusicEditHotbarActions.create(mapping);

        assertEquals(MusicEditHotbarActions.Action.PITCH_UP, actions.actionFor('X'));
        assertEquals(List.of('X'), actions.duplicateChars(mapping),
                "a shared character is a config mistake worth reporting");
    }

    @Test
    void nonButtonCharactersResolveToNothing() {
        MusicEditHotbarActions actions = MusicEditHotbarActions.create(Map.of());

        assertNull(actions.actionFor('X'), "the edit area uses its own character");
        assertNull(actions.actionFor(' '));
        assertNull(actions.actionFor('#'));
    }

    @Test
    void everyActionDeclaresTheConfigKeyTheGuiReads() {
        for (MusicEditHotbarActions.Action action : MusicEditHotbarActions.Action.values()) {
            assertEquals(action.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    action.configKey(),
                    "a mismatched key would silently fall back to the default character");
        }
        assertEquals(MusicEditHotbarActions.Action.values().length,
                java.util.Arrays.stream(MusicEditHotbarActions.Action.values())
                        .map(MusicEditHotbarActions.Action::configKey)
                        .distinct()
                        .count(),
                "two actions sharing a config key would read the same button definition");
    }

    @Test
    void aCleanConfigurationReportsNoDuplicates() {
        assertEquals(List.of(), MusicEditHotbarActions.create(Map.of()).duplicateChars(Map.of()));
    }
}
