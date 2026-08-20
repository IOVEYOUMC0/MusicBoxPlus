package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;

import java.util.concurrent.*;
import java.util.logging.Level;

public final class AsyncTaskManager {
    private static volatile AsyncTaskManager instance;
    // Once shutdown() has run, getInstance() must NOT build a fresh manager: onDisable keeps tearing
    // down subsystems that still fire runAsync(), and a new manager there would create thread pools
    // nobody ever shuts down -> leaked pools on every disable/re-enable inside the same JVM
    // (PlugMan, /reload). reset() re-arms this for a genuine restart on the same classloader.
    private static volatile boolean terminated = false;
    private static final Object LOCK = new Object();

    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService scheduledExecutor;
    private volatile boolean shutdown = false;

    public static AsyncTaskManager getInstance() {
        AsyncTaskManager local = instance;
        if (local != null) {
            return local;
        }
        synchronized (LOCK) {
            if (instance == null && !terminated) {
                instance = new AsyncTaskManager();
            }
            return instance;
        }
    }

    private AsyncTaskManager() {
        int maxThreads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        this.asyncExecutor = Executors.newFixedThreadPool(maxThreads, r -> {
            Thread thread = new Thread(r);
            thread.setName("MusicBox-Async-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });

        this.scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r);
            thread.setName("MusicBox-Scheduled-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void executeAsync(Runnable task) {
        if (shutdown) {
            return;
        }
        asyncExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(Level.WARNING, "Async task execution failed", e);
            }
        });
    }

    public void executeAsync(Runnable task, Runnable onFailure) {
        if (shutdown) {
            return;
        }
        asyncExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(Level.WARNING, "Async task execution failed", e);
                if (onFailure != null) {
                    try {
                        onFailure.run();
                    } catch (Exception ex) {
                        MusicBox.getInstance().getLogger().log(Level.WARNING, "Failure callback execution failed", ex);
                    }
                }
            }
        });
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        if (shutdown) {
            return null;
        }
        return scheduledExecutor.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(Level.WARNING, "Scheduled task execution failed", e);
            }
        }, initialDelay, period, unit);
    }

    public void schedule(Runnable task, long delay, TimeUnit unit) {
        if (shutdown) {
            return;
        }
        scheduledExecutor.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(Level.WARNING, "Delayed task execution failed", e);
            }
        }, delay, unit);
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        // Blocks getInstance() from rebuilding pools: late runAsync() calls during the rest of
        // onDisable get this shut-down instance, whose submit paths short-circuit on `shutdown`.
        terminated = true;

        MusicBox.getInstance().getLogger().info("AsyncTaskManager is shutting down...");

        asyncExecutor.shutdown();
        scheduledExecutor.shutdown();

        try {
            int pendingTasks = 0;
            if (!asyncExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                pendingTasks = asyncExecutor.shutdownNow().size();
                if (pendingTasks > 0) {
                    MusicBox.getInstance().getLogger().warning("Forced async executor shutdown dropped " + pendingTasks + " pending task(s)");
                }
            }
            if (!scheduledExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                pendingTasks = scheduledExecutor.shutdownNow().size();
                if (pendingTasks > 0) {
                    MusicBox.getInstance().getLogger().warning("Forced scheduled executor shutdown dropped " + pendingTasks + " pending task(s)");
                }
            }
            MusicBox.getInstance().getLogger().info("AsyncTaskManager shutdown complete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MusicBox.getInstance().getLogger().warning("AsyncTaskManager shutdown was interrupted");
            asyncExecutor.shutdownNow();
            scheduledExecutor.shutdownNow();
        }

        // `instance` is deliberately left non-null: a late getInstance() must return this no-op
        // manager instead of resurrecting fresh pools. reset() is the explicit path to clear it.
    }

    public static void reset() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.shutdown();
            }
            // Re-arm so the next getInstance() can build a fresh manager (soft reload reusing statics).
            terminated = false;
            instance = null;
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public static void runAsync(Runnable task) {
        AsyncTaskManager mgr = getInstance();
        if (mgr != null) {
            mgr.executeAsync(task);
        }
    }

    public static void runAsync(Runnable task, Runnable onFailure) {
        AsyncTaskManager mgr = getInstance();
        if (mgr != null) {
            mgr.executeAsync(task, onFailure);
        }
    }
}
