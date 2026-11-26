package com.huidu.musicboxplus.module.edit;

/**
 * "Has this song changed since the last successful save?" with the version rule that makes an
 * in-flight save safe.
 *
 * The rule exists because saving is asynchronous while editing is not. An auto-save captures the
 * version it saw, the player keeps editing, and when the save completes it must clear the dirty flag
 * only if nothing changed meanwhile -- otherwise the edits made during the save would never be
 * written. Conversely a failed save must put the flag back, but again only if the player has not
 * since made a change that already set it.
 *
 * Kept as a plain object so the rule can be tested without a player, a scheduler or a database.
 * The editor's field is volatile today because the scheduler hop reads it from another thread; this
 * class keeps it volatile for the same reason.
 */
final class MusicEditDirtyState {

    private volatile boolean dirty;
    private volatile long version;

    /** Records one change and returns the version to compare against after the save completes. */
    long markChanged() {
        dirty = true;
        return ++version;
    }

    boolean isDirty() {
        return dirty;
    }

    long version() {
        return version;
    }

    /**
     * Clears the flag only when no change happened since savedVersion. Returns true when the flag was
     * cleared, false when newer edits kept it set.
     */
    boolean clearIfUnchangedSince(long savedVersion) {
        if (version != savedVersion) {
            return false;
        }
        dirty = false;
        return true;
    }

    /** Puts the flag back after a failed save, unless newer edits already set it. */
    void restoreIfUnchangedSince(long savedVersion) {
        if (version == savedVersion) {
            dirty = true;
        }
    }

    /** Clears the flag unconditionally, for paths that discard the edits. */
    void clear() {
        dirty = false;
    }
}
