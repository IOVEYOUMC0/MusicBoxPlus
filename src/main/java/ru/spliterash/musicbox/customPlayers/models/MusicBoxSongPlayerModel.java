package ru.spliterash.musicbox.customPlayers.models;

import com.xxmicloxx.NoteBlockAPI.model.SoundCategory;
import com.xxmicloxx.NoteBlockAPI.songplayer.SongPlayer;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.spliterash.musicbox.MusicBox;
import ru.spliterash.musicbox.customPlayers.interfaces.IPlayList;
import ru.spliterash.musicbox.customPlayers.interfaces.MusicBoxSongPlayer;
import ru.spliterash.musicbox.gui.song.SPControlGUI;
import ru.spliterash.musicbox.song.MusicBoxSong;

import java.util.*;
import java.util.function.Function;

@Getter
public class MusicBoxSongPlayerModel {
    private final static Set<MusicBoxSongPlayerModel> all = Collections.newSetFromMap(new WeakHashMap<>());
    private final MusicBoxSongPlayer musicBoxSongPlayer;
    private final IPlayList playList;
    private final Function<IPlayList, ? extends MusicBoxSongPlayer> nextSongRunnable;
    private boolean run = false;
    private boolean nextCreated = false;
    private boolean songEndNormal = false;

    /**
     * @param songPlayer       плеер который связан с этой моделью
     * @param playList         плейлист который сейчас играет
     * @param nextSongRunnable как ставить следующую музыку из плейлиста
     */
    public MusicBoxSongPlayerModel(MusicBoxSongPlayer songPlayer, IPlayList playList, Function<IPlayList, ? extends MusicBoxSongPlayer> nextSongRunnable) {
        all.add(this);
        this.musicBoxSongPlayer = songPlayer;
        this.playList = playList;
        this.nextSongRunnable = nextSongRunnable;
    }

    public static void destroyAll() {
        List<MusicBoxSongPlayerModel> modelsToDestroy = new ArrayList<>(all);
        for (MusicBoxSongPlayerModel model : modelsToDestroy) {
            if (model != null && model.getMusicBoxSongPlayer() != null) {
                if (!model.getMusicBoxSongPlayer().isDestroyed()) {
                    model.getMusicBoxSongPlayer().destroy();
                }
            }
        }
        all.clear();
    }

    public MusicBoxSong getCurrentSong() {
        return playList.getCurrent();
    }

    public void runPlayer() {
        if (!run) {
            SongPlayer apiSongPlayer = this.musicBoxSongPlayer.getApiPlayer();
            if (apiSongPlayer == null) {
                MusicBox.getInstance().getLogger().warning("MusicBoxSongPlayerModel: apiSongPlayer is null in runPlayer.");
                return;
            }

            if (MusicBox.getInstance().getConfigObject().isEnable10octave()) {
                apiSongPlayer.setEnable10Octave(true);
            }
            apiSongPlayer.setCategory(SoundCategory.RECORDS);
            apiSongPlayer.setPlaying(true);
            run = true;
        }
    }

    public void destroy() {
        if (controlGUI != null) {
            controlGUI.close();
            controlGUI = null;
        }
    }

    private SPControlGUI controlGUI;

    public SPControlGUI getControlGUI() {
        if (controlGUI == null) {
            controlGUI = new SPControlGUI(this);
        }
        return controlGUI;
    }

    public void setPlayers(Collection<UUID> newPlayerUUIDs) {
        SongPlayer apiSongPlayer = this.musicBoxSongPlayer.getApiPlayer();
        if (apiSongPlayer == null) {
            MusicBox.getInstance().getLogger().warning("MusicBoxSongPlayerModel: apiSongPlayer is null in setPlayers, cannot update listeners.");
            return;
        }

        Set<UUID> currentPlayerUUIDs = new HashSet<>(apiSongPlayer.getPlayerUUIDs());
        Set<UUID> targetPlayerUUIDs = new HashSet<>(newPlayerUUIDs);

        if (currentPlayerUUIDs.equals(targetPlayerUUIDs)) {
            return;
        }

        Set<UUID> uuidsToRemove = new HashSet<>(currentPlayerUUIDs);
        uuidsToRemove.removeAll(targetPlayerUUIDs);

        Set<UUID> uuidsToAdd = new HashSet<>(targetPlayerUUIDs);
        uuidsToAdd.removeAll(currentPlayerUUIDs);

        for (UUID uuid : uuidsToRemove) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                apiSongPlayer.removePlayer(player);
            }
        }

        for (UUID uuid : uuidsToAdd) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                apiSongPlayer.addPlayer(player);
            }
        }
    }

    private void acceptNext() {
        MusicBoxSongPlayer nextPlayer = nextSongRunnable.apply(playList);
        if (nextPlayer != null && controlGUI != null) {
            controlGUI.openNext(nextPlayer.getMusicBoxModel());
        }
    }

    public void onSongEnd() {
        startNext();
    }

    public void createNextPlayer() {
        this.nextCreated = true;
        if (this.musicBoxSongPlayer != null && !this.musicBoxSongPlayer.isDestroyed()) {
            this.musicBoxSongPlayer.destroy();
        }
        acceptNext();
    }

    public void pingSongEnded() {
        this.songEndNormal = true;
    }

    public void startNext() {
        if (playList.tryNext()) {
            createNextPlayer();
        } else {
            if (this.musicBoxSongPlayer != null && !this.musicBoxSongPlayer.isDestroyed()) {
                this.musicBoxSongPlayer.destroy();
            }
        }
    }
}