package com.huidu.musicboxplus.module.edit;

import java.util.function.BooleanSupplier;

/** Entity-thread state machine. No Bukkit dependency: callbacks must be dispatched to the owner first. */
final class MusicEditSessionGuard {
    private enum State { EDITING, CONFIRMING, SAVING, CLOSED }
    private final BooleanSupplier currentSession;
    private State state = State.EDITING;
    private long generation;

    MusicEditSessionGuard(BooleanSupplier currentSession) {
        this.currentSession = currentSession;
    }

    boolean canEdit() {
        return state == State.EDITING && currentSession.getAsBoolean();
    }

    boolean isTransitioning() {
        return state != State.EDITING;
    }

    long closeRequestToken() {
        return generation;
    }

    void runCloseRequest(long token, Runnable action) {
        if (generation == token && canEdit()) action.run();
    }

    long beginConfirmation() {
        if (!canEdit()) return -1;
        state = State.CONFIRMING; // Before opening another inventory (which fires a close event).
        return ++generation;
    }

    Runnable confirmationAction(long token, Runnable action) {
        return () -> {
            if (state != State.CONFIRMING || generation != token || !currentSession.getAsBoolean()) return;
            state = State.EDITING;
            ++generation; // One choice only, including delayed ESC and double clicks.
            action.run();
        };
    }

    long beginSave() {
        if (!canEdit()) return -1;
        state = State.SAVING;
        return ++generation;
    }

    void completeSave(long token, boolean success, Runnable saved, Runnable failed) {
        if (state != State.SAVING || generation != token || !currentSession.getAsBoolean()) return;
        state = State.EDITING;
        ++generation;
        if (success) saved.run(); else failed.run();
    }

    boolean beginClose() {
        if (!currentSession.getAsBoolean() || state == State.CLOSED) return false;
        state = State.CLOSED;
        ++generation;
        return true;
    }

    void invalidate() {
        state = State.CLOSED;
        ++generation;
    }

    void runIfEditing(Runnable action) {
        if (canEdit()) action.run();
    }
}
