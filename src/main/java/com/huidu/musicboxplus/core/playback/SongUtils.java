package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import com.huidu.musicboxplus.common.lang.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public final class SongUtils {
    public static String getNum(int num) {
        if (num > -1) {
            return Lang.PLAYLIST_SONG_NUM.toString("{num}", String.valueOf(num + 1));
        }
        return "";
    }

    public static String getSongName(int i, MusicBoxSong song, boolean glow) {
        String num = SongUtils.getNum(i);
        return (glow ? Lang.CURRENT_PLAYLIST_SONG : Lang.ANOTHER_PLAYLIST_SONG).toString("{song}", song.getName(), "{num}", num);
    }

    public static String getCompactSongName(MusicBoxSong song, boolean current) {
        if (song == null) {
            return current ? "<green>-</green>" : "<gray>-</gray>";
        }
        return (current ? "<green>" : "<gray>") + song.getName() + (current ? "</green>" : "</gray>");
    }

    public static List<String> generatePlaylistLore(IPlayList list, int prevCount, int postCount) {
        ArrayList<String> lore = new ArrayList<String>(prevCount + postCount + 1);
        List<? extends MusicBoxSong> prevList = list.getPrevSongs(prevCount);
        Collections.reverse(prevList);
        for (MusicBoxSong song : prevList) {
            lore.add(SongUtils.getSongName(list.getSongNum(song), song, false));
        }
        MusicBoxSong current = list.getCurrent();
        lore.add(SongUtils.getSongName(list.getSongNum(current), current, true));
        for (MusicBoxSong song : list.getNextSongs(postCount)) {
            lore.add(SongUtils.getSongName(list.getSongNum(song), song, false));
        }
        return lore;
    }

    public static List<String> generateCompactPlaylistLore(IPlayList list, int prevCount, int postCount) {
        ArrayList<String> lore = new ArrayList<>(prevCount + postCount + 1);
        List<? extends MusicBoxSong> prevList = list.getPrevSongs(prevCount);
        Collections.reverse(prevList);
        for (MusicBoxSong song : prevList) {
            lore.add(getCompactSongName(song, false));
        }
        MusicBoxSong current = list.getCurrent();
        lore.add(getCompactSongName(current, true));
        for (MusicBoxSong song : list.getNextSongs(postCount)) {
            lore.add(getCompactSongName(song, false));
        }
        return lore;
    }

    public static Function<IPlayList, PlayerSongPlayer> nextPlayerSong(PlayerWrapper wrapper) {
        return list -> {
            wrapper.play(list);
            return wrapper.getActivePlayer();
        };
    }

    private SongUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
