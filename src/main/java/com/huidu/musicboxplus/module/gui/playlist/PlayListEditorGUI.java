package com.huidu.musicboxplus.module.gui.playlist;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.classes.PeekList;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.types.FullSongContainer;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.gui.layout.LayoutParser;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public class PlayListEditorGUI {
    private static final long DIRTY_EXIT_CONFIRM_WINDOW_MS = 3000L;
    private final PlayerPlayListModel model;
    private final PlayerWrapper wrapper;
    private final GUIConfigManager configManager;
    private boolean saveInProgress = false;
    private boolean dirty = false;
    private long lastDirtyExitWarningAt = 0L;
    private GUI currentGUI;
    private int currentPage = 0;
    private List<Integer> songSlots;
    private boolean playlistMutationInProgress = false;

    public PlayListEditorGUI(PlayerWrapper wrapper, PlayerPlayListModel model) {
        this.wrapper = wrapper;
        this.model = model;
        this.configManager = GUIConfigManager.getInstance();
    }

    public void openPage(int page) {
        int last = this.getLastPage();
        int clampedPage = Math.max(0, Math.min(page, last - 1));
        String title = this.configManager.getGUITitle("playlist-editor");
        if (title == null || title.isEmpty()) {
            title = "<gold>Playlist <dark_gray>- <yellow>{playlist} <gray>({page}/{last_page})";
        }
        title = title.replace("{playlist}", this.model.getName())
            .replace("{page}", String.valueOf(clampedPage + 1))
            .replace("{last_page}", String.valueOf(last));

        GUI gui = new GUI(title);
        this.currentGUI = gui;
        this.currentPage = clampedPage;

        LayoutParser layoutParser = new LayoutParser(gui, "playlist-editor");
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("playlist-editor").getButtonMapping();
        if (clampedPage > 0) {
            int prevPage = clampedPage - 1;
            layoutParser.registerSimpleButton(mapping.getBack(), "back", () -> this.openPage(prevPage));
        }
        if (last - 1 > clampedPage) {
            int nextPage = clampedPage + 1;
            layoutParser.registerSimpleButton(mapping.getNext(), "next", () -> this.openPage(nextPage));
        }

        layoutParser.registerButton(mapping.getShuffle(), () -> this.configManager.createButtonItem("playlist-editor", "shuffle-playlist"), () -> new ClickAction(() -> {
            Collections.shuffle(this.model.getSongs());
            this.dirty = true;
            MessageUtils.send(this.wrapper.getPlayer(), Lang.DONT_FORGET_TO_SAVE);
            this.refreshSongList();
        }));
        layoutParser.registerButton(mapping.getAdd(), () -> this.configManager.createButtonItem("playlist-editor", "add-music-to-playlist"), () -> new ClickAction(() -> GUIActions.openPlayListAdder(this.wrapper, this)));
        layoutParser.registerButton(mapping.getSave(), this::createSaveButton, () -> new ClickAction(() -> {
            if (!this.model.getSongs().isEmpty()) {
                if (this.saveInProgress) {
                    MessageUtils.send(this.wrapper.getPlayer(), Lang.CHILL_CHILL_MAN);
                } else {
                    Player savePlayer = this.wrapper.getPlayer();
                    if (savePlayer == null) {
                        return;
                    }
                    this.saveInProgress = true;
                    AsyncTaskManager.runAsync(() -> {
                        boolean saved = this.model.save();
                        Scheduler.entity(savePlayer, () -> {
                            if (saved) {
                                this.dirty = false;
                                MessageUtils.send(savePlayer, Lang.PLAYLIST_SAVED, "{playlist}", this.model.getName());
                            } else {
                                RuntimeDatabaseUtils.notifyUnavailable(savePlayer);
                            }
                            this.saveInProgress = false;
                        });
                    });
                }
            } else {
                MessageUtils.send(this.wrapper.getPlayer(), Lang.PLAYLIST_ZERO_SIZE);
            }
        }));
        layoutParser.registerButton(mapping.getDelete(), () -> this.configManager.createButtonItem("playlist-editor", "delete-playlist"), () -> new ClickAction(() -> {
            DeleteConfirmGUI confirmGUI = new DeleteConfirmGUI(
                wrapper.getPlayer(),
                model.getName(),
                () -> {
                    Player deletePlayer = wrapper.getPlayer();
                    if (deletePlayer == null) {
                        return;
                    }
                    AsyncTaskManager.runAsync(() -> {
                        boolean deleted = model.delete();
                        Scheduler.entity(deletePlayer, () -> {
                            if (deleted) {
                                MessageUtils.send(deletePlayer, Lang.PLAYLIST_DELETED, "{name}", model.getName());
                            } else {
                                RuntimeDatabaseUtils.notifyUnavailable(deletePlayer);
                            }
                        });
                    });
                },
                () -> openPage(currentPage)
            );
            confirmGUI.open();
        }));
        layoutParser.registerPlayerButton(mapping.getReturnBtn(), "back", p -> {
            if (this.shouldConfirmDirtyExit()) {
                MessageUtils.send(this.wrapper.getPlayer(), Lang.PLAYLIST_UNSAVED_EXIT_CONFIRM);
                return;
            }
            PlayListListGUI.openAsync(this.wrapper, container -> new ClickAction(() -> this.wrapper.play(container), () -> {
                if (container instanceof PlayerPlayListModel) {
                    new PlayListEditorGUI(this.wrapper, (PlayerPlayListModel) container).openPage(0);
                }
            }), pl -> this.configManager.getPlaylistItemConfig().getListLore(), () -> this.openPage(currentPage));
        });

        String layout = this.configManager.getGUILayout("playlist-editor");
        if (layout != null && !layout.isEmpty()) {
            layoutParser.parseAndApply(layout);
        }
        this.songSlots = layoutParser.getSlotsForChar(mapping.getSongs());
        this.updateSongList();
        gui.open(this.wrapper.getPlayer());
    }

    private ItemStack createSaveButton() {
        ItemStack item = this.configManager.createButtonItem("playlist-editor", "save-playlist-change");
        if (item != null) {
            return item;
        }
        return ItemUtils.createStack(Material.PAPER, "<gold>Save Changes", null);
    }

    private void updateSongList() {
        if (this.currentGUI == null || this.songSlots == null) {
            return;
        }

        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("playlist-editor").getButtonMapping();
        int slotsPerPage = this.configManager.getLayoutCharCount("playlist-editor", mapping.getSongs());
        if (slotsPerPage <= 0) {
            slotsPerPage = 36;
        }
        int start = slotsPerPage * this.currentPage;
        PeekList<Material> list = new PeekList<>(BukkitUtils.DISCS);
        List<MusicBoxSong> songs = this.model.getSongs();

        int itemIndex = 0;
        for (int slot : this.songSlots) {
            this.currentGUI.removeItem(slot);
            int arrayIndex = start + itemIndex;
            if (arrayIndex < songs.size()) {
                MusicBoxSong song = songs.get(arrayIndex);
                List<String> songLore = this.configManager.getPlaylistItemConfig().getItemLore();
                ItemStack stack = song.getSongStack(list.getAndNext(), songLore, false);
                // Capture the song, not its index: the list shifts under an in-flight removal and
                // removeSong resolves by identity anyway.
                this.currentGUI.addItem(slot, stack, new ClickAction(null, () -> this.removeSongAsync(song)));
            }
            itemIndex++;
        }
    }

    public void refreshSongList() {
        int lastPage = this.getLastPage();
        if (this.currentPage >= lastPage) {
            this.currentPage = Math.max(0, lastPage - 1);
        }
        this.updateSongList();
    }

    private boolean shouldConfirmDirtyExit() {
        if (!this.dirty) {
            this.lastDirtyExitWarningAt = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastDirtyExitWarningAt <= DIRTY_EXIT_CONFIRM_WINDOW_MS) {
            this.lastDirtyExitWarningAt = 0L;
            return false;
        }
        this.lastDirtyExitWarningAt = now;
        return true;
    }

    private int getLastPage() {
        GUIConfigManager.ButtonMappingConfig mapping = this.configManager.getGUIConfig("playlist-editor").getButtonMapping();
        int slotsPerPage = this.configManager.getLayoutCharCount("playlist-editor", mapping.getSongs());
        if (slotsPerPage <= 0) {
            slotsPerPage = 36;
        }
        return Math.max(1, (int) Math.ceil((double) this.model.getSongs().size() / (double) slotsPerPage));
    }

    public boolean addSong(MusicBoxSong song) {
        return this.model.addSong(song);
    }

    public void addSongAsync(MusicBoxSong song, Runnable onSuccess) {
        if (song == null || this.playlistMutationInProgress) {
            return;
        }
        Player player = this.wrapper.getPlayer();
        if (player == null) {
            return;
        }
        this.playlistMutationInProgress = true;
        AsyncTaskManager.runAsync(() -> {
            boolean added = this.model.addSong(song);
            Scheduler.entity(player, () -> {
                this.playlistMutationInProgress = false;
                if (added) {
                    this.refreshSongList();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else {
                    RuntimeDatabaseUtils.notifyUnavailable(player);
                }
            });
        });
    }

    public void removeSongAsync(MusicBoxSong song) {
        if (song == null || this.playlistMutationInProgress) {
            return;
        }
        Player player = this.wrapper.getPlayer();
        if (player == null) {
            return;
        }
        this.playlistMutationInProgress = true;
        AsyncTaskManager.runAsync(() -> {
            boolean removed = this.model.removeSong(song);
            Scheduler.entity(player, () -> {
                this.playlistMutationInProgress = false;
                if (removed) {
                    this.refreshSongList();
                } else {
                    RuntimeDatabaseUtils.notifyUnavailable(player);
                }
            });
        });
    }

    public void addContainerAsync(FullSongContainer container, java.util.function.IntConsumer onSuccess) {
        if (container == null || this.playlistMutationInProgress) {
            return;
        }
        Player player = this.wrapper.getPlayer();
        if (player == null) {
            return;
        }
        this.playlistMutationInProgress = true;
        AsyncTaskManager.runAsync(() -> {
            int count = addContainerSync(container);
            Scheduler.entity(player, () -> {
                this.playlistMutationInProgress = false;
                if (count >= 0) {
                    this.refreshSongList();
                    if (onSuccess != null) {
                        onSuccess.accept(count);
                    }
                } else {
                    RuntimeDatabaseUtils.notifyUnavailable(player);
                }
            });
        });
    }

    private int addContainerSync(FullSongContainer container) {
        LinkedHashSet<MusicBoxSong> songsToAdd = new LinkedHashSet<>();
        collectContainerSongs(container, songsToAdd);
        songsToAdd.removeIf(this::hasSong);
        if (songsToAdd.isEmpty()) {
            return 0;
        }
        return this.model.addSongsBulk(List.copyOf(songsToAdd)) ? songsToAdd.size() : -1;
    }

    private void collectContainerSongs(FullSongContainer container, LinkedHashSet<MusicBoxSong> songsToAdd) {
        songsToAdd.addAll(container.getSongs());
        for (FullSongContainer fullSongContainer : container.getSubContainers()) {
            collectContainerSongs(fullSongContainer, songsToAdd);
        }
    }

    public boolean hasSong(MusicBoxSong s) {
        return this.model.getSongs().contains(s);
    }
}
