package com.huidu.musicboxplus.module.edit;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone regression suite; javac + java, no Bukkit, JUnit or MockBukkit required. */
public final class MusicEditSessionGuardTest {
    private static int checks;

    @org.junit.jupiter.api.Test
    void runSessionRegressionSuiteInGradle() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        confirmationReentryAndOneShotChoices();
        failedSaveReopensAndCanRetry();
        lateCallbacksCannotTouchReplacement();
        lateReleaseCannotUnlockNewSession();
        admissionAndConstructionFailure();
        concurrentAdmissionIsExclusive();
        System.out.println("MusicEditSessionGuardTest: " + checks + " checks passed");
    }

    private static void confirmationReentryAndOneShotChoices() {
        MusicEditSessionGuard guard = new MusicEditSessionGuard(() -> true);
        long queuedClose = guard.closeRequestToken();
        long token = guard.beginConfirmation();
        check(token > 0 && guard.isTransitioning(), "claim before openInventory emits close");
        check(guard.beginConfirmation() < 0, "synchronous/queued close cannot open second confirmation");
        int[] choices = {0};
        Runnable cancel = guard.confirmationAction(token, () -> choices[0]++);
        Runnable save = guard.confirmationAction(token, () -> choices[0] += 10);
        cancel.run();
        save.run();
        cancel.run();
        check(choices[0] == 1 && guard.canEdit(), "ESC/click/duplicate callbacks have one winner");
        guard.runCloseRequest(queuedClose, () -> { throw new AssertionError("stale queued close reopened confirmation"); });
        check(guard.canEdit(), "cancel/reopen invalidates previously queued editor-close request");
        long next = guard.beginConfirmation();
        cancel.run();
        check(guard.isTransitioning(), "previous prompt cannot cancel next prompt");
        guard.confirmationAction(next, () -> choices[0]++).run();
        check(choices[0] == 2, "fresh prompt remains usable");
    }

    private static void failedSaveReopensAndCanRetry() {
        MusicEditSessionGuard guard = new MusicEditSessionGuard(() -> true);
        int[] saved = {0};
        int[] reopened = {0};
        long token = guard.beginSave();
        check(!guard.canEdit() && guard.beginSave() < 0, "save in flight blocks duplicate save and edits");
        guard.completeSave(token, false, () -> saved[0]++, () -> {
            check(guard.canEdit(), "failure returns to editable before reopen/restart autosave");
            reopened[0]++;
        });
        guard.completeSave(token, true, () -> saved[0]++, () -> reopened[0]++);
        check(reopened[0] == 1 && saved[0] == 0, "failed completion is consumed once");
        long retry = guard.beginSave();
        guard.completeSave(retry, true, () -> {
            saved[0]++;
            check(guard.beginClose(), "successful save can finish close");
        }, () -> reopened[0]++);
        check(saved[0] == 1 && !guard.canEdit(), "retry succeeds and closes");
        check(!guard.beginClose(), "finishClose is idempotent");
    }

    private static void lateCallbacksCannotTouchReplacement() {
        MusicEditSessionRegistry<Object> registry = new MusicEditSessionRegistry<>();
        UUID owner = UUID.randomUUID(), music = UUID.randomUUID();
        Object old = new Object(), replacement = new Object();
        registry.admit(owner, music, old);
        MusicEditSessionGuard guard = new MusicEditSessionGuard(() -> registry.isCurrent(owner, old));
        long token = guard.beginSave();
        registry.admit(owner, music, replacement);
        int[] inventoryOrLocksTouched = {0};
        Runnable dangerous = () -> inventoryOrLocksTouched[0]++;
        guard.completeSave(token, true, dangerous, dangerous);
        guard.completeSave(token, false, dangerous, dangerous);
        guard.runIfEditing(dangerous); // manual/BPM/autosave callbacks use this exact production gate
        check(!guard.beginClose(), "queued finishClose cannot act on replacement");
        check(inventoryOrLocksTouched[0] == 0, "late success/failure/render never touch replacement");

        MusicEditSessionGuard promptGuard = new MusicEditSessionGuard(() -> registry.isCurrent(owner, replacement));
        long prompt = promptGuard.beginConfirmation();
        registry.admit(owner, music, new Object());
        promptGuard.confirmationAction(prompt, dangerous).run();
        check(inventoryOrLocksTouched[0] == 0, "late ESC cancel cannot reopen old editor");
    }

    private static void lateReleaseCannotUnlockNewSession() {
        MusicEditSessionRegistry<Object> registry = new MusicEditSessionRegistry<>();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), song = UUID.randomUUID();
        Object old = new Object(), replacement = new Object();
        registry.admit(owner, song, old);
        registry.admit(owner, song, replacement);
        check(!registry.release(owner, song, old), "same-owner same-song ABA release rejected");
        check(!registry.admit(other, song, new Object()).admitted(), "replacement music lease preserved");
        check(registry.release(owner, song, replacement), "current exact session can release");
        Object newOwner = new Object();
        check(registry.admit(other, song, newOwner).admitted(), "other owner admitted after release");
        check(!registry.release(owner, song, old), "old owner cannot release another player's lease");
        check(registry.isCurrent(other, newOwner), "new owner's GUI remains installed");
    }

    private static void admissionAndConstructionFailure() {
        MusicEditSessionRegistry<Object> registry = new MusicEditSessionRegistry<>();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        UUID oldSong = UUID.randomUUID(), nextSong = UUID.randomUUID();
        Object old = new Object();
        registry.admit(owner, oldSong, old);
        try {
            Object constructed = failingFactory(); // production constructs before registry.admit
            registry.admit(owner, nextSong, constructed);
            throw new AssertionError("factory should fail");
        } catch (IllegalStateException expected) {
            check(registry.isCurrent(owner, old), "construction failure preserves previous GUI");
            check(registry.admit(other, nextSong, new Object()).admitted(), "construction failure leaves no new lock");
        }
        Object next = new Object();
        registry.release(other, nextSong, registry.get(other));
        check(registry.admit(owner, nextSong, next).previous() == old, "admission returns previous GUI for teardown");
        check(registry.admit(other, oldSong, new Object()).admitted(), "song switch releases previous lease");
    }

    private static Object failingFactory() {
        throw new IllegalStateException("invalid GUI configuration");
    }

    private static void concurrentAdmissionIsExclusive() throws Exception {
        MusicEditSessionRegistry<Object> registry = new MusicEditSessionRegistry<>();
        UUID song = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        Runnable enter = () -> {
            try {
                start.await();
                if (registry.admit(UUID.randomUUID(), song, new Object()).admitted()) admitted.incrementAndGet();
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
        };
        Thread first = new Thread(enter), second = new Thread(enter);
        first.start(); second.start(); start.countDown(); first.join(); second.join();
        check(admitted.get() == 1, "concurrent regions admit only one owner");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
