package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.common.utils.classes.PeekList;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

final class ControlPanelSongListRenderer {
    private ControlPanelSongListRenderer() {
    }

    static void render(
        GUI gui,
        LayoutParser layoutParser,
        GUIConfigManager configManager,
        IPlayList list,
        boolean canSwitch,
        Consumer<MusicBoxSong> onSelect
    ) {
        if (list == null || list.getCurrent() == null) {
            GUIConfigManager.GUIConfig guiConfig = configManager.getGUIConfig("control-panel");
            GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getButtonMapping();
            List<Integer> songSlots = layoutParser.getSlotsForChar(mapping.getSongList());
            if (songSlots.isEmpty()) {
                songSlots = IntStream.range(9, 18).boxed().toList();
            }
            for (int slot : songSlots) {
                gui.addItem(slot, null, null);
            }
            return;
        }

        List<? extends MusicBoxSong> prev = list.getPrevSongs(4);
        Collections.reverse(prev);
        List<? extends MusicBoxSong> next = list.getNextSongs(4);
        PeekList<Material> peekList = new PeekList<Material>(BukkitUtils.DISCS);
        MusicBoxSong currentSong = list.getCurrent();
        GUIConfigManager.GUIConfig guiConfig = configManager.getGUIConfig("control-panel");
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getButtonMapping();
        List<Integer> songSlots = layoutParser.getSlotsForChar(mapping.getSongList());
        if (songSlots.isEmpty()) {
            songSlots = IntStream.range(9, 18).boxed().toList();
        }
        for (int slot : songSlots) {
            gui.addItem(slot, null, null);
        }
        int midIndex = songSlots.size() / 2;
        int startFrom = midIndex - prev.size();
        for (int i = 0; i < prev.size(); i++) {
            int index = i + startFrom;
            if (index >= 0 && index < songSlots.size()) {
                MusicBoxSong song = prev.get(i);
                addDiscItem(gui, songSlots.get(index), song, peekList, false, list.getSongNum(song), canSwitch, onSelect);
            }
        }
        if (midIndex < songSlots.size()) {
            addDiscItem(gui, songSlots.get(midIndex), currentSong, peekList, true, list.getSongNum(currentSong), canSwitch, onSelect);
        }
        for (int i = 0; i < next.size(); i++) {
            int index = i + midIndex + 1;
            if (index < songSlots.size()) {
                MusicBoxSong song = next.get(i);
                addDiscItem(gui, songSlots.get(index), song, peekList, false, list.getSongNum(song), canSwitch, onSelect);
            }
        }
    }

    private static void addDiscItem(
        GUI gui,
        int index,
        MusicBoxSong song,
        PeekList<Material> peekList,
        boolean playNow,
        int songNum,
        boolean canSwitch,
        Consumer<MusicBoxSong> onSelect
    ) {
        ItemStack item = song.getSongStack(peekList.getAndNext(), SongUtils.getSongName(songNum, song, playNow), null, playNow);
        ClickAction action = canSwitch ? new ClickAction(() -> onSelect.accept(song)) : null;
        gui.addItem(index, item, action);
    }
}
