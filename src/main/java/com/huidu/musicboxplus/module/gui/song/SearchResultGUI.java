package com.huidu.musicboxplus.module.gui.song;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.IntStream;

public class SearchResultGUI {
    public static void open(PlayerWrapper wrapper, List<MusicBoxSong> results, String query) {
        open(wrapper, results, query, null, null, "song-list", null);
    }

    public static void open(PlayerWrapper wrapper, List<MusicBoxSong> results, String query, SongContainerGUI sourceGui, SongContainerGUI.SongGUIParams params, String guiType, Runnable backAction) {
        int pageCount;
        GUIConfigManager configManager = GUIConfigManager.getInstance();
        GUIConfigManager.ButtonMappingConfig mapping = configManager.getGUIConfig("search-results").getButtonMapping();
        int slotsPerPage = configManager.getLayoutCharCount("search-results", mapping.getResults());
        if (slotsPerPage <= 0) {
            slotsPerPage = 45;
        }
        if ((pageCount = (int)Math.ceil((double)results.size() / (double)slotsPerPage)) == 0) {
            pageCount = 1;
        }
        SearchResultGUI.openPage(wrapper, results, query, 0, pageCount, slotsPerPage, sourceGui, params, guiType, backAction);
    }

    private static void openPage(PlayerWrapper wrapper, List<MusicBoxSong> results, String query, int page, int pageCount, int slotsPerPage, SongContainerGUI sourceGui, SongContainerGUI.SongGUIParams params, String guiType, Runnable backAction) {
        GUIConfigManager configManager = GUIConfigManager.getInstance();
        String title = configManager.getGUITitle("search-results");
        if (title == null || title.isEmpty()) {
            title = "<gold>Search Results <gray>- <yellow>{query} <gray>({page}/{last_page})";
        }
        title = title.replace("{query}", query).replace("{page}", String.valueOf(page + 1)).replace("{last_page}", String.valueOf(pageCount));
        GUI gui = new GUI(title);
        LayoutParser layoutParser = new LayoutParser(gui, "search-results");
        GUIConfigManager.ButtonMappingConfig mapping = configManager.getGUIConfig("search-results").getButtonMapping();
        layoutParser.registerSimpleButton(mapping.getBack(), "back", backAction != null ? backAction : () -> com.huidu.musicboxplus.module.gui.GUIActions.openDefaultInventory(wrapper));
        if (page > 0) {
            int prevPage = page - 1;
            layoutParser.registerSimpleButton(mapping.getPrevious(), "previous", () -> SearchResultGUI.openPage(wrapper, results, query, prevPage, pageCount, slotsPerPage, sourceGui, params, guiType, backAction));
        }
        if (page < pageCount - 1) {
            int nextPage = page + 1;
            layoutParser.registerSimpleButton(mapping.getNext(), "next", () -> SearchResultGUI.openPage(wrapper, results, query, nextPage, pageCount, slotsPerPage, sourceGui, params, guiType, backAction));
        }
        String layout = configManager.getGUILayout("search-results");
        if (layout != null && !layout.isEmpty()) {
            layoutParser.parseAndApply(layout);
        }
        List<Integer> resultSlots = layoutParser.getSlotsForChar(mapping.getResults());
        if (resultSlots.isEmpty()) {
            resultSlots = IntStream.range(0, 45).boxed().toList();
        }
        int startIndex = page * resultSlots.size();
        for (int i = 0; i < resultSlots.size() && startIndex + i < results.size(); ++i) {
            MusicBoxSong song = results.get(startIndex + i);
            int slot = resultSlots.get(i);
            List<String> lore;
            if (params != null && params.getExtraSongLore() != null && sourceGui != null) {
                SongContainerGUI.SongGUIData<MusicBoxSong> songData = sourceGui.createSongData(song, params, page, guiType);
                lore = params.getExtraSongLore().apply(songData);
            } else {
                lore = song.shouldUseVanillaJukeboxPlayback()
                        ? List.of(Lang.SEARCH_RECORD_USE_DISC.toString())
                        : configManager.getShopLoreConfig().getSearchLore();
            }
            ItemStack stack = song.getSongStack(Material.MUSIC_DISC_CAT, lore, false);
            gui.addItem(slot, stack, new ClickAction(() -> {
                if (params != null && params.getOnSongLeftClick() != null && sourceGui != null) {
                    params.getOnSongLeftClick().accept(wrapper, sourceGui.createSongData(song, params, page, guiType));
                    return;
                }
                if (song.shouldUseVanillaJukeboxPlayback()) {
                    MessageUtils.send(wrapper.getPlayer(), Lang.SEARCH_RECORD_USE_DISC);
                    return;
                }
                wrapper.play(new SingletonPlayList(song));
            }));
        }
        gui.open(wrapper.getPlayer());
    }
}
