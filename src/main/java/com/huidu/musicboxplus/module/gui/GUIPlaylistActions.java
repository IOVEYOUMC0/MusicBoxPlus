package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.playlist.PlayListEditorGUI;
import com.huidu.musicboxplus.module.gui.playlist.PlayListListGUI;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;

import java.util.List;

final class GUIPlaylistActions {
    private GUIPlaylistActions() {
    }

    static void openPlaylistListEditor(PlayerWrapper wrapper) {
        List<String> list = GUIConfigManager.getInstance().getPlaylistItemConfig().getListLore();
        PlayListListGUI.openAsync(wrapper, model -> new ClickAction(() -> wrapper.play(ListPlaylist.fromContainer(model, false, false)), () -> {
            if (model instanceof PlayerPlayListModel) {
                openPlaylistEditor(wrapper, (PlayerPlayListModel) model);
            }
        }), model -> list);
    }

    static void openPlaylistEditor(PlayerWrapper wrapper, PlayerPlayListModel model) {
        new PlayListEditorGUI(wrapper, model).openPage(0);
    }

    static void openPlayListAdder(PlayerWrapper wrapper, PlayListEditorGUI editorGUI) {
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        GUIConfigManager.PlaylistItemConfig playlistConfig = GUIConfigManager.getInstance().getPlaylistItemConfig();
        SongContainerGUI.SongGUIParams params = SongContainerGUI.SongGUIParams.builder().onSongLeftClick((w, data) -> {
            MusicBoxSong song = data.getData();
            editorGUI.addSongAsync(song, () -> {
                MessageUtils.send(w.getPlayer(), Lang.SONG_ADDED, "{song}", song.getName());
                editorGUI.openPage(0);
            });
        }).onContainerRightClick((w, data) -> {
            FullSongContainer container = data.getData();
            editorGUI.addContainerAsync(container, count -> {
                MessageUtils.send(w.getPlayer(), Lang.CONTAINER_ADDED, "{container}", container.getName(), "{count}", String.valueOf(count));
                editorGUI.openPage(0);
            });
        }).extraSongLore(data -> {
            MusicBoxSong song = data.getData();
            if (editorGUI.hasSong(song)) {
                return playlistConfig.getAddSongExistsLore();
            }
            return playlistConfig.getAddSongLore();
        }).extraContainerLore(data -> playlistConfig.getAddContainerLore()).build();
        gui.openPage(0, params);
    }
}
