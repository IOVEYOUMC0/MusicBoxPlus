package com.huidu.musicboxplus.module.gui.playlist;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.InventoryAction;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public class PlayListListGUI {
    private List<PlayerPlayListModel> list;
    private final PlayerWrapper wrapper;
    private final GUIConfigManager configManager;

    private PlayListListGUI(PlayerWrapper wrapper) {
        this.wrapper = wrapper;
        this.list = Collections.emptyList();
        this.configManager = GUIConfigManager.getInstance();
    }

    public static void openAsync(PlayerWrapper wrapper, Function<SongContainer, InventoryAction> onSelect, Function<PlayerPlayListModel, List<String>> extraLore) {
        openAsync(wrapper, onSelect, extraLore, null);
    }

    public static void openAsync(PlayerWrapper wrapper, Function<SongContainer, InventoryAction> onSelect, Function<PlayerPlayListModel, List<String>> extraLore, Runnable backAction) {
        PlayListListGUI gui = new PlayListListGUI(wrapper);
        Player player = wrapper.getPlayer();
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();

        AsyncTaskManager.runAsync(() -> {
            try {
                List<PlayerPlayListModel> playlists = DatabaseLoader.getBase().getPlayLists(playerId);
                gui.list = playlists != null ? playlists : Collections.emptyList();
                Scheduler.entity(player, () -> gui.openPage(0, onSelect, extraLore, backAction));
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("load playlists", e);
                Scheduler.entity(player, () -> RuntimeDatabaseUtils.notifyUnavailable(player));
            }
        });
    }

    public static void openAsync(PlayerWrapper wrapper) {
        openAsync(wrapper, null, null);
    }

    public void openPage(int page, Function<SongContainer, InventoryAction> onSelect, Function<PlayerPlayListModel, List<String>> extraLore) {
        openPage(page, onSelect, extraLore, null);
    }

    public void openPage(int page, Function<SongContainer, InventoryAction> onSelect, Function<PlayerPlayListModel, List<String>> extraLore, Runnable backAction) {
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("playlist-list").getButtonMapping();
        int slotsPerPage = this.configManager.getLayoutCharCount("playlist-list", mapping.getPlaylist());
        if (slotsPerPage <= 0) {
            slotsPerPage = 36;
        }

        int lastPage = this.getLastPage();
        int clampedPage = Math.max(0, Math.min(page, lastPage - 1));
        int offset = clampedPage * slotsPerPage;
        String title = this.configManager.getGUITitle("playlist-list");
        if (title == null || title.isEmpty()) {
            title = "<gold>Playlist Editor <gray>({page}/{last_page})";
        }
        title = title.replace("{page}", String.valueOf(clampedPage + 1)).replace("{last_page}", String.valueOf(lastPage));

        GUI gui = new GUI(title);
        LayoutParser layoutParser = new LayoutParser(gui, "playlist-list");
        layoutParser.registerSimpleButton(mapping.getBack(), "back", backAction != null ? backAction : () -> GUIActions.openDefaultInventory(this.wrapper));

        if (clampedPage > 0) {
            int prevPage = clampedPage - 1;
            layoutParser.registerSimpleButton(mapping.getPrevious(), "previous", () -> this.openPage(prevPage, onSelect, extraLore, backAction));
        }
        if (lastPage > clampedPage + 1) {
            int nextPage = clampedPage + 1;
            layoutParser.registerSimpleButton(mapping.getNext(), "next", () -> this.openPage(nextPage, onSelect, extraLore, backAction));
        }

        layoutParser.registerSimpleButton(mapping.getCreate(), "create-playlist", () -> GUIInputManager.getInstance().requestPlaylistNameInput(this.wrapper));
        layoutParser.registerButton(mapping.getMaster(), this::createMasterPlaylistButton, () -> onSelect != null ? onSelect.apply(MusicBoxSongManager.getMasterContainer()) : null);
        layoutParser.registerSimpleButton(mapping.getClose(), "close", () -> {
            Player p = this.wrapper.getPlayer();
            if (p != null) {
                p.closeInventory();
            }
        });

        String layout = this.configManager.getGUILayout("playlist-list");
        if (layout != null && !layout.isEmpty()) {
            layoutParser.parseAndApply(layout);
        }

        List<Integer> playlistSlots = layoutParser.getSlotsForChar(mapping.getPlaylist());
        GUIConfigManager.PlaylistItemConfig playlistConfig = this.configManager.getPlaylistItemConfig();
        int itemIndex = 0;
        for (int slot : playlistSlots) {
            int listIndex = offset + itemIndex;
            if (listIndex >= this.list.size()) {
                break;
            }

            PlayerPlayListModel element = this.list.get(listIndex);
            List<String> baseLore = playlistConfig.getLoreFormat().isEmpty()
                    ? playlistConfig.getListLore()
                    : playlistConfig.getLoreFormat();
            ArrayList<String> lore = new ArrayList<>(baseLore);
            lore.replaceAll(line -> line
                .replace("{count}", String.valueOf(element.getSongs().size()))
                .replace("{duration}", StringUtils.toHumanTime(element.getSongs().stream().mapToInt(MusicBoxSong::getDuration).sum()))
            );
            if (extraLore != null) {
                lore.addAll(extraLore.apply(element));
            }

            String playlistName = playlistConfig.getNameFormat().replace("{playlist}", element.getName());
            ItemStack stack = ItemUtils.createStack(Material.PAPER, playlistName, lore);
            gui.addItem(slot, stack, onSelect != null ? onSelect.apply(element) : null);
            ++itemIndex;
        }
        gui.open(this.wrapper.getPlayer());
    }

    private ItemStack createMasterPlaylistButton() {
        ItemStack item = this.configManager.createButtonItem("playlist-list", "master-playlist");
        if (item != null) {
            return item;
        }
        return ItemUtils.createStack(Material.ENCHANTED_BOOK, "<gold>Master Playlist", this.configManager.getPlaylistItemConfig().getDefaultLore());
    }

    private int getLastPage() {
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("playlist-list").getButtonMapping();
        int slotsPerPage = this.configManager.getLayoutCharCount("playlist-list", mapping.getPlaylist());
        if (slotsPerPage <= 0) {
            slotsPerPage = 36;
        }
        return Math.max(1, (int) Math.ceil((double) this.list.size() / (double) slotsPerPage));
    }
}
