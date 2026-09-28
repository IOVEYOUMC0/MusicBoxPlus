package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.common.config.SmartConfigManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// Static state that has to be dropped when the plugin is disabled on a classloader that will be
// re-enabled again (PlugMan-style reload).
//
// Every registry here is static, so a re-enable does not get a fresh copy of it -- the same objects
// come back, and anything a session left in them is still reachable, still called, and still pins the
// previous session's players and GUIs. These tests cover the ones that are now cleared, and they are
// careful about the one that must NOT be, for the opposite reason: MusicEditListener.GUI_HANDLERS is
// filled by a static initialiser that does not run a second time, so clearing it would leave every
// GUI click unhandled forever.
class StaticRegistryShutdownTest {

    @Test
    void unregisterDropsPendingTextInputSessions() {
        UUID player = UUID.randomUUID();
        MusicEditTextInputManager.restoreTextInputSession(player, session());
        assertTrue(MusicEditTextInputManager.hasTextInputSession(player));

        MusicEditListener.unregister();

        assertFalse(MusicEditTextInputManager.hasTextInputSession(player),
                "a session from the previous enable would answer into a GUI that no longer exists");
    }

    @Test
    void unregisterIsRepeatable() {
        MusicEditListener.unregister();
        MusicEditListener.unregister();

        assertFalse(MusicEditTextInputManager.hasTextInputSession(UUID.randomUUID()));
    }

    @Test
    void aSessionStartedAfterTheShutdownSurvives() {
        // Clearing is a one-shot: the listener is registered again on the next enable, and a session
        // started from that point on must be answered normally.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        MusicEditTextInputManager.restoreTextInputSession(first, session());
        MusicEditListener.unregister();
        MusicEditTextInputManager.restoreTextInputSession(second, session());

        assertFalse(MusicEditTextInputManager.hasTextInputSession(first));
        assertTrue(MusicEditTextInputManager.hasTextInputSession(second),
                "only sessions that existed before the shutdown may be dropped");
    }

    @Test
    void smartConfigManagersAreDroppedAtShutdown() {
        // The plugin argument is only stored; nothing in the constructor reads it, so this needs no
        // server -- which is the point: the registry is cleared without touching config loading.
        new SmartConfigManager(null, "shutdown-test.yml", "shutdown-test.yml");
        assertTrue(SmartConfigManager.getInstances().containsKey("shutdown-test.yml"));

        SmartConfigManager.shutdown();

        assertTrue(SmartConfigManager.getInstances().isEmpty(),
                "the static registry pinned the previous session's plugin through each manager");
    }

    private static MusicEditTextInputManager.TextInputData session() {
        return new MusicEditTextInputManager.TextInputData(null, "name", value -> { }, () -> { });
    }
}
