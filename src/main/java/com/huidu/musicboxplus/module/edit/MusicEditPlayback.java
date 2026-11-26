package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;

/**
 * The editor's playback clock: advances the song tick by tick and tells the view what changed.
 *
 * Split out of MusicEditGUI, which mixed the timing/advancement rules with rendering and
 * click handling. Those rules are the part with the subtle invariants -- a tick may catch up several
 * song ticks per server tick, the page has to follow the notes, and finishing must not be confused
 * with stopping -- so they read better on their own.
 *
 * All view interaction goes through Host; this class never touches an inventory directly.
 */
final class MusicEditPlayback {

    /** The editor window as this clock sees it. */
    interface Host {
        Player player();

        PlayerMusic music();

        MusicEditLayout layout();

        int columns();

        int rows();

        int tickOffset();

        void tickOffset(int tickOffset);

        boolean playing();

        void playing(boolean playing);

        int playTick();

        void playTick(int playTick);

        /** Reused across ticks; the clock clears it before filling it. */
        Set<Integer> changedSlots();

        List<MbTask> scheduledTasks();

        void refreshAll();

        void refreshSlots(Set<Integer> slots);

        void refreshHotbar();

        void cancelQueuedRender();

        void clearPreviewHighlight();

        void playNote(int pitch, MusicNote.NoteInstrument instrument);
    }

    private final Host host;

    MusicEditPlayback(Host host) {
        this.host = host;
    }

    void play() {
        playFromTick(0);
    }

    void playFromTick(int startTick) {
        Player player = host.player();
        PlayerMusic music = host.music();
        host.playing(true);
        host.scheduledTasks().clear();
        host.playTick(-1);

        if (music.getNoteCount() == 0) {
            host.playing(false);
            host.playTick(-1);
            host.refreshAll();
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_PLAY);
            return;
        }

        int bpm = music.getBpm();
        double tickDurationMillis = 60000.0 / (bpm * music.getBeatSubdivision());
        int editCols = host.columns();

        // Playback reads the song through PlayerMusic's per-tick accessors instead of taking a copy
        // of the whole tick index first. getTickIndexMap() returns `new TreeMap<>(tickIndexMap)`, so
        // pressing play built a full copy of every note in the song on the main thread (100k notes =
        // 100k tree entries) and the loop then walked an Integer[] of every remaining tick, boxed.
        // Both are gone: the loop asks for the notes at one tick, which is a small list copy.
        int from = Math.max(0, startTick);
        if (music.firstNoteTickAtOrAfter(from) == null) {
            host.playing(false);
            host.playTick(-1);
            host.refreshAll();
            MessageUtils.send(player, Lang.EDIT_NO_NOTES_TO_PLAY);
            return;
        }

        startTimedPlayback(music.lastNoteTick(), from, tickDurationMillis, editCols);

        MessageUtils.send(player, Lang.EDIT_PLAYBACK_STARTED);
    }

    private void startTimedPlayback(int endTick, int startTick, double tickDurationMillis, int editCols) {
        final int[] timelineTick = {startTick};
        final double[] accumulator = {tickDurationMillis};
        final long[] lastNano = {System.nanoTime()};
        final int maxBurstPerServerTick = 32;

        MbTask task = Scheduler.entityTimer(host.player(), () -> {
            if (!host.playing()) {
                return;
            }

            long now = System.nanoTime();
            accumulator[0] += (now - lastNano[0]) / 1_000_000.0;
            lastNano[0] = now;

            int processed = 0;
            boolean needsInventoryRefresh = false;
            boolean needsHotbarRefresh = false;
            boolean needsFullInventoryRefresh = false;
            // Reused across ticks: this runs every server tick while a song plays.
            Set<Integer> changedSlots = host.changedSlots();
            changedSlots.clear();
            while (accumulator[0] >= tickDurationMillis && timelineTick[0] <= endTick && processed < maxBurstPerServerTick) {
                accumulator[0] -= tickDurationMillis;
                int previousPlayTick = host.playTick();
                int previousTickOffset = host.tickOffset();
                List<MusicNote> tickNotes = host.music().getNotesAtTick(timelineTick[0]);
                if (!tickNotes.isEmpty()) {
                    needsHotbarRefresh |= playTick(tickNotes, timelineTick[0], editCols);
                } else {
                    needsHotbarRefresh |= updatePosition(timelineTick[0], editCols);
                }
                if (host.tickOffset() != previousTickOffset) {
                    needsFullInventoryRefresh = true;
                } else {
                    addChangedSlots(changedSlots, previousPlayTick, editCols);
                    addChangedSlots(changedSlots, host.playTick(), editCols);
                }
                needsInventoryRefresh = true;
                timelineTick[0]++;
                processed++;
            }

            if (timelineTick[0] > endTick) {
                finish();
                return;
            }

            if (needsInventoryRefresh) {
                if (needsHotbarRefresh) {
                    host.refreshHotbar();
                }
                if (needsFullInventoryRefresh) {
                    host.refreshAll();
                } else if (!changedSlots.isEmpty()) {
                    host.refreshSlots(changedSlots);
                }
            }
        }, 0L, 1L);

        host.scheduledTasks().add(task);
    }

    private boolean playTick(List<MusicNote> tickNotes, int tick, int editCols) {
        boolean needsHotbarRefresh = updatePosition(tick, editCols);

        for (MusicNote note : tickNotes) {
            for (MusicNote.NoteInstrument instrument : note.getInstruments()) {
                host.playNote(note.getPitch(), instrument);
            }
        }
        return needsHotbarRefresh;
    }

    private boolean updatePosition(int tick, int editCols) {
        host.playTick(tick);

        if (currentPageHasRemainingNotes(tick, editCols)) {
            return false;
        }

        Integer nextNoteTick = host.music().firstNoteTickAtOrAfter(tick);
        if (nextNoteTick == null) {
            return false;
        }

        int nextOffset = nextNoteTick / editCols;
        if (nextOffset == host.tickOffset()) {
            return false;
        }
        host.tickOffset(nextOffset);
        return true;
    }

    private boolean currentPageHasRemainingNotes(int tick, int editCols) {
        int pageStart = host.tickOffset() * editCols;
        int pageEnd = pageStart + editCols - 1;
        Integer nextVisibleNoteTick = host.music().firstNoteTickAtOrAfter(Math.max(tick, pageStart));
        return nextVisibleNoteTick != null && nextVisibleNoteTick <= pageEnd;
    }

    private void addChangedSlots(Set<Integer> changedSlots, int tick, int editCols) {
        if (tick < 0) {
            return;
        }
        int localCol = tick - host.tickOffset() * editCols;
        if (localCol < 0 || localCol >= editCols) {
            return;
        }
        int editRows = host.rows();
        List<Integer> editArea = host.layout().editAreaSlots();
        for (int localRow = 0; localRow < editRows; localRow++) {
            int index = localRow * editCols + localCol;
            if (index < editArea.size()) {
                changedSlots.add(editArea.get(index));
            }
        }
    }

    private void finish() {
        if (!host.playing()) {
            return;
        }
        host.cancelQueuedRender();
        host.playing(false);
        host.playTick(-1);
        cancelTasks();
        host.refreshAll();
        host.refreshHotbar();
    }

    void stop() {
        host.cancelQueuedRender();
        host.playing(false);
        host.playTick(-1);
        host.clearPreviewHighlight();
        cancelTasks();
        host.refreshAll();
        MessageUtils.send(host.player(), Lang.EDIT_PLAYBACK_STOPPED);
    }

    private void cancelTasks() {
        for (MbTask task : host.scheduledTasks()) {
            task.cancel();
        }
        host.scheduledTasks().clear();
    }
}
