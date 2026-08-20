package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.EconomyUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.classes.PeekList;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class RecentSongsGUI {
    private final PlayerWrapper wrapper;
    private final GUIConfigManager configManager;
    private final boolean buyMode;
    private final Runnable backAction;

    public RecentSongsGUI(PlayerWrapper wrapper) {
        this(wrapper, false, null);
    }

    public RecentSongsGUI(PlayerWrapper wrapper, boolean buyMode, Runnable backAction) {
        this.wrapper = wrapper;
        this.configManager = GUIConfigManager.getInstance();
        this.buyMode = buyMode;
        this.backAction = backAction;
    }

    public void openPage(int page) {
        String layout;
        List<MusicBoxSong> recentSongs = this.wrapper.getRecentSongs();
        if (!this.buyMode) {
            recentSongs = recentSongs.stream().filter(song -> !song.shouldUseVanillaJukeboxPlayback()).toList();
        }
        int indexLimit = 36;
        layout = this.configManager.getGUILayout("recent-songs");
        if (layout != null && !layout.isEmpty()) {
            int count = 0;
            GUIConfigManager.GUIConfig guiConfig = this.configManager.getGUIConfig("recent-songs");
            char songChar = guiConfig.getButtonMapping().getSongs();
            for (char c : layout.toCharArray()) {
                if (c != songChar) continue;
                ++count;
            }
            if (count > 0) {
                indexLimit = count;
            }
        }
        int lastPage = this.getLastPage(recentSongs, indexLimit);
        int clampedPage = Math.max(0, Math.min(page, lastPage - 1));
        String title = (this.buyMode ? Lang.RECENT_SONGS_BUY_TITLE : Lang.RECENT_SONGS_TITLE).toString().replace("{page}", String.valueOf(clampedPage + 1)).replace("{last_page}", String.valueOf(lastPage));
        GUI gui = new GUI(title);
        LayoutParser layoutParser = new LayoutParser(gui, "recent-songs");
        GUIConfigManager.GUIConfig guiConfig = this.configManager.getGUIConfig("recent-songs");
        GUIConfigManager.ButtonMappingConfig mapping = guiConfig.getButtonMapping();
        if (this.backAction != null) {
            layoutParser.registerSimpleButton(mapping.getBack(), "back", this.backAction);
        } else {
            layoutParser.registerPlayerButton(mapping.getBack(), "back", p -> GUIActions.openDefaultInventory(this.wrapper));
        }
        if (clampedPage > 0) {
            int prevPage = clampedPage - 1;
            layoutParser.registerSimpleButton(mapping.getPrevious(), "previous", () -> this.openPage(prevPage));
        }
        if (lastPage - 1 > clampedPage) {
            int nextPage = clampedPage + 1;
            layoutParser.registerSimpleButton(mapping.getNext(), "next", () -> this.openPage(nextPage));
        }
        if (layout != null && !layout.isEmpty()) {
            layoutParser.parseAndApply(layout);
        }
        int skipElements = clampedPage * indexLimit;
        PeekList<Material> discList = new PeekList<Material>(BukkitUtils.DISCS);
        List<Integer> songSlots = layoutParser.getSlotsForChar(mapping.getSongs());
        int itemIndex = skipElements;
        for (int slot : songSlots) {
            if (itemIndex >= recentSongs.size()) break;
            MusicBoxSong song = recentSongs.get(itemIndex);
            ++itemIndex;
            ItemStack stack = song.getSongStack(discList.getAndNext(), null, false);
            if (this.buyMode) {
                double price = EconomyUtils.getDiscPrice();
                List<String> lore = new java.util.ArrayList<>();
                for (String line : GUIConfigManager.getInstance().getShopLoreConfig().getShopLore()) {
                    lore.add(line.replace("{price}", String.format("%.0f", price)));
                }
                stack = ItemUtils.addLore(stack, lore);
                gui.addItem(slot, stack, new ClickAction(() -> GUIActions.playerBuyMusic(this.wrapper, song)));
                continue;
            }
            gui.addItem(slot, stack, new ClickAction(() -> this.wrapper.play(song)));
        }
        gui.open(this.wrapper.getPlayer());
    }

    private int getLastPage(List<MusicBoxSong> recentSongs, int slotsPerPage) {
        if (recentSongs.isEmpty()) {
            return 1;
        }
        return Math.max(1, (int)Math.ceil((double)recentSongs.size() / (double) Math.max(1, slotsPerPage)));
    }
}
