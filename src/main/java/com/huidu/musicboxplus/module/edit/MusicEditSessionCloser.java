package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Ending an editing session: retire the session, close the window, and hand the player's own
 * inventory back -- but only if this window is still the current one.
 *
 * The ordering is the whole point, and is why it lived inline in the window where it could only be
 * reviewed by reading around it. Everything runs on the owner's region, checked at execution time
 * rather than when the close was requested (Folia: the player may have moved regions in between).
 * The session is invalidated before the inventory closes, so the close event cannot be mistaken for
 * the player reopening the editor. And the inventory is restored only if no other window took over
 * during that close event: a synchronous close handler can admit a new session, and restoring would
 * then wipe the new window's items.
 */
final class MusicEditSessionCloser {

    /** The window state ending a session touches. */
    interface Host {
        Player player();

        /** Begins the close handshake; false when the session already moved on. */
        boolean beginClose();

        /** Session invalidation, task cancellation, playback reset. */
        void retireSession();

        /** True while this window is still the session the listener tracks for that player. */
        boolean isCurrentSession();

        void closeInventory();

        /** Returns the player's saved inventory and forgets the saved state. */
        void restoreInventory();

        /** Leaves edit mode in the listener, passing this window as the expected session. */
        void exitEditMode();
    }

    private final Host host;

    MusicEditSessionCloser(Host host) {
        this.host = host;
    }

    /** Normal close: retire, close, restore unless another window claimed the player meanwhile. */
    void finishClose() {
        Scheduler.entityNow(host.player(), () -> {
            if (!host.beginClose()) {
                return;
            }
            host.retireSession();
            host.closeInventory();
            // A synchronous close event may admit another GUI. Never restore its inventory.
            if (host.isCurrentSession()) {
                host.restoreInventory();
                host.exitEditMode();
            }
        });
    }

    /**
     * Cleanup for a window that failed to open. Same order, but it also refuses to act when this
     * window was never the current session, so a late failure cannot tear down its replacement.
     */
    void abortFailedOpen() {
        Scheduler.entityNow(host.player(), () -> {
            if (!host.isCurrentSession()) {
                return;
            }
            host.retireSession();
            host.closeInventory();
            if (host.isCurrentSession()) {
                host.restoreInventory();
                host.exitEditMode();
            }
        });
    }
}
