package ru.spliterash.musicbox.customPlayers.objects;

import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;
import ru.spliterash.musicbox.customPlayers.interfaces.IPlayList;
import ru.spliterash.musicbox.customPlayers.interfaces.PlayerSongPlayer;
import ru.spliterash.musicbox.customPlayers.models.MusicBoxSongPlayerModel;
import ru.spliterash.musicbox.customPlayers.models.PlayerPlayerModel;
import ru.spliterash.musicbox.players.PlayerWrapper;
import ru.spliterash.musicbox.utils.SongUtils;

/**
 * Проигрыватель для игрока
 * Если игрок захочет послушать музыку никого не напрягая
 */
@Getter
public class RadioPlayer extends RadioSongPlayer implements PlayerSongPlayer {
    private final PlayerPlayerModel model;
    private final MusicBoxSongPlayerModel musicBoxModel;

    private boolean destroyed = false;

    public RadioPlayer(IPlayList list, PlayerWrapper wrapper) {
        super(list.getCurrent().getSong());
        this.musicBoxModel = new MusicBoxSongPlayerModel(this, list, SongUtils.nextPlayerSong(wrapper));
        this.model = new PlayerPlayerModel(wrapper, musicBoxModel);
        musicBoxModel.runPlayer();

    }


    @Override
    public void destroy() {
        if (!isDestroyed()) {
            try {
                super.destroy();
            } catch (IllegalPluginAccessException ex) {
                // ПОФИК
            }
            model.destroy();
            musicBoxModel.destroy();
            destroyed = true;
        }
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void playTick(@NotNull Player player, int tick) {
        super.playTick(player, tick);
        if (player.equals(model.getWrapper().getPlayer())) {
            model.nextTick(getSong().getLength(), tick);
        }
    }
}
