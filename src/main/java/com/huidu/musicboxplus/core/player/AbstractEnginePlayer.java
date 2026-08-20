package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.engine.PlaybackClock;
import com.huidu.musicboxplus.core.engine.PlaybackCursor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Base for everything that plays a song: holds the listeners, the volume settings and the
// cursor, and turns "a tick came due" into per-listener work on the right thread.
//
// One clock drives every player on the server. A player that is not playing reports no next
// tick, so it costs nothing while idle.
//
// Sound is produced by playTick, which always runs on the thread that owns the listener. The
// tick is passed as an argument rather than staged in shared fields, so a listener can never
// observe a tick belonging to another listener's slice, and the tick a player reports is the
// one it is actually on.
public abstract class AbstractEnginePlayer implements MusicBoxSongPlayer {

    // Shared by every player. Started on first use and never stopped: it parks when nothing is
    // registered.
    private static final PlaybackClock CLOCK = new PlaybackClock(System::nanoTime, "MusicBox-Playback");

    // Lazily started, but never restarted: destroy() runs during onDisable, after the clock has
    // been stopped, and starting a thread there leaves it running past plugin shutdown.
    private static volatile boolean clockShutDown = false;

    private static PlaybackClock clock() {
        if (!CLOCK.isRunning() && !clockShutDown) {
            CLOCK.start();
        }
        return CLOCK;
    }

    public static void shutdownClock() {
        clockShutDown = true;
        CLOCK.shutdown();
    }

    public static int activePlayerCount() {
        return CLOCK.targetCount();
    }

    // Listeners, mapped to whether they were last seen in range. Written from each listener's
    // own thread during playTick.
    protected final Map<UUID, Boolean> playerList = new ConcurrentHashMap<>();

    protected volatile byte volume = 100;
    protected volatile boolean enable10Octave;
    protected volatile SoundCategory soundCategory = SoundCategory.RECORDS;

    private final PlaybackCursor cursor;
    private volatile int distance = 16;
    private volatile boolean destroyed;
    private volatile boolean songEndReported;

    private final PlaybackClock.Target target = new PlaybackClock.Target() {
        @Override
        public PlaybackCursor cursor() {
            return cursor;
        }

        @Override
        public void playTicks(int firstTick, int count) {
            dispatchTicks(firstTick, count);
        }

        @Override
        public void songFinished() {
            reportSongEnd();
        }

        @Override
        public boolean alive() {
            return !destroyed;
        }
    };

    protected AbstractEnginePlayer(CompiledSong song) {
        this.cursor = new PlaybackCursor(song);
        clock().register(target);
    }

    // Where playback events belong. Song end can build or destroy block players, so it has to
    // run on the region owning that block; a player without a location uses the global region.
    protected abstract Location dispatchLocation();

    // Produce this tick's sound for one listener. Runs on the listener's own thread.
    protected abstract void playTick(Player listener, int tick);

    // Called once when the song runs out, on the region from dispatchLocation.
    protected abstract void onSongFinished();

    private void dispatchTicks(int firstTick, int count) {
        if (destroyed || playerList.isEmpty()) {
            return;
        }
        // The clock runs on its own thread, so a tick can come due in the middle of onDisable.
        // Scheduling on a disabled plugin throws, and Bukkit logs the whole stack trace.
        MusicBox musicBox = MusicBox.getInstance();
        if (musicBox == null || musicBox.isShuttingDown() || !musicBox.isEnabled()) {
            return;
        }
        // 普通 Paper/Spigot 上所有实体调度最终都落在同一主线程，逐玩家调度纯属浪费
        // （每 tick 每玩家一次队列投递 + 闭包分配）。直接收集玩家一次批量执行，
        // Folia 上仍然按玩家逐实体调度到各自区域线程。
        if (!Scheduler.isFolia()) {
            if (!Bukkit.isPrimaryThread()) {
                // 时钟线程 -> 主线程，一次投递处理全部玩家
                Bukkit.getGlobalRegionScheduler().execute(musicBox, () -> dispatchTicksInline(firstTick, count));
            } else {
                dispatchTicksInline(firstTick, count);
            }
            return;
        }
        for (UUID uuid : playerList.keySet()) {
            Player listener = Bukkit.getPlayer(uuid);
            if (listener == null || !listener.isOnline()) {
                continue;
            }
            Scheduler.entity(listener, () -> {
                if (destroyed) {
                    return;
                }
                for (int i = 0; i < count; i++) {
                    playTick(listener, firstTick + i);
                }
            });
        }
    }

    private void dispatchTicksInline(int firstTick, int count) {
        for (UUID uuid : playerList.keySet()) {
            Player listener = Bukkit.getPlayer(uuid);
            if (listener == null || !listener.isOnline()) {
                continue;
            }
            if (destroyed) {
                return;
            }
            for (int i = 0; i < count; i++) {
                playTick(listener, firstTick + i);
            }
        }
    }

    private void reportSongEnd() {
        if (destroyed || songEndReported) {
            return;
        }
        songEndReported = true;
        Location at = dispatchLocation();
        Runnable finish = () -> {
            // Re-checked on the region thread: setSong()/setTick() issued after the report re-arm
            // the flag, and a freshly restarted song must not be treated as finished again.
            if (!destroyed && songEndReported) {
                onSongFinished();
            }
        };
        if (at != null) {
            Scheduler.region(at, finish);
        } else {
            Scheduler.global(finish);
        }
    }

    public CompiledSong getSong() {
        return cursor.song();
    }

    // Replacing the song restarts it and re-arms the end report, which is what repeat-one and a
    // track change both need.
    public void setSong(CompiledSong song) {
        cursor.setSong(song);
        songEndReported = false;
    }

    @Override
    public short getTick() {
        return (short) cursor.tick();
    }

    @Override
    public void setTick(int tick) {
        cursor.seek(tick);
        songEndReported = false;
    }

    @Override
    public boolean isPlaying() {
        return cursor.isPlaying();
    }

    @Override
    public void setPlaying(boolean playing) {
        cursor.setPlaying(playing);
        if (playing) {
            clock().register(target);
        }
    }

    public float getPlaybackSpeed() {
        return cursor.speed();
    }

    public void setPlaybackSpeed(float speed) {
        cursor.setSpeed(speed);
    }

    public int getDistance() {
        return distance;
    }

    public void setDistance(int distance) {
        this.distance = Math.max(0, distance);
    }

    public void setEnable10Octave(boolean enable10Octave) {
        this.enable10Octave = enable10Octave;
    }

    public boolean isEnable10Octave() {
        return enable10Octave;
    }

    public void addPlayer(Player player) {
        if (player != null) {
            playerList.putIfAbsent(player.getUniqueId(), true);
        }
    }

    public void removePlayer(Player player) {
        if (player != null) {
            playerList.remove(player.getUniqueId());
        }
    }

    // The listener's own volume setting, clamped. Shared by the players that follow a person
    // around; a block player scales by distance instead.
    protected int resolvePlaybackVolume(Player listener) {
        if (listener == null) {
            return 100;
        }
        return Math.max(0, Math.min(100, VolumeManager.getPlayerVolume(listener)));
    }

    public void removePlayer(UUID uuid) {
        if (uuid != null) {
            playerList.remove(uuid);
        }
    }

    @Override
    public Set<UUID> getPlayers() {
        return playerList.keySet();
    }

    // Whether the player tears itself down as soon as the last listener leaves. Always off:
    // MusicBox runs its own idle timeout instead, which survives a listener stepping out of
    // range for a moment.
    public boolean getAutoDestroy() {
        return false;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        cursor.setPlaying(false);
        clock().unregister(target);
        playerList.clear();
    }
}
