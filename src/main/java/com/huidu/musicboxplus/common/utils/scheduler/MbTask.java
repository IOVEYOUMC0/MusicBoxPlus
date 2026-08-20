package com.huidu.musicboxplus.common.utils.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

// Null-safe cancellable handle for a Paper/Folia ScheduledTask, so call sites need not
// import the Paper scheduler type. A null task is a valid state: the entity scheduler
// returns null when the target entity was already removed/retired before the task could
// be registered.
public final class MbTask {
    @Nullable
    private final ScheduledTask task;

    private MbTask(@Nullable ScheduledTask task) {
        this.task = task;
    }

    public static MbTask of(@Nullable ScheduledTask task) {
        return new MbTask(task);
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isCancelled() {
        return task == null || task.isCancelled();
    }

    @Nullable
    public ScheduledTask handle() {
        return task;
    }
}
