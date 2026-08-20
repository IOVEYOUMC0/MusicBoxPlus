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

    void flash(Player player, int pitch, int tick, Runnable onUpdate) {
        this.pitch = pitch;
        this.tick = tick;
        if (this.task != null) {
            this.task.cancel();
        }
        onUpdate.run();
        Runnable expiry = () -> {
            this.pitch = -1;
            this.tick = -1;
            this.task = null;
            onUpdate.run();
        };
        // Folia: the expiry callback runs onUpdate (MusicEditGUI.updateInventory), which mutates the
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
