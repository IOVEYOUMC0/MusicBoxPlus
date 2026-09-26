package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.entity.Player;

final class MusicEditPreviewHighlighter {
    private int pitch = -1;
    private int tick = -1;
    private MbTask task;

    boolean isHighlightTick(int value) {
        return this.tick == value;
    }

    boolean isHighlightNote(int pitch, int tick) {
        return this.tick == tick && this.pitch == pitch;
    }

    // onActivate runs with the highlight set, onExpire after it has been cleared. Two callbacks
    // rather than one because the caller has to repaint the cells the flash *was* on, and by the time
    // expiry runs this object no longer remembers them.
    void flash(Player player, int pitch, int tick, Runnable onActivate, Runnable onExpire) {
        this.pitch = pitch;
        this.tick = tick;
        if (this.task != null) {
            this.task.cancel();
        }
        onActivate.run();
        Runnable expiry = () -> {
            this.pitch = -1;
            this.tick = -1;
            this.task = null;
            onExpire.run();
        };
        // Folia: the expiry callback runs the caller's inventory update, which mutates the
        // player's open inventory and must run on that player's own region, not the global region thread.
        this.task = player != null
                ? Scheduler.entityLater(player, expiry, 6L)
                : Scheduler.globalLater(expiry, 6L);
    }

    void clear() {
        this.pitch = -1;
        this.tick = -1;
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
