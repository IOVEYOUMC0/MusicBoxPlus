package com.huidu.musicboxplus.module.gui.textplayer;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.gui.minecraft.actions.ClickAction;
import com.huidu.musicboxplus.module.gui.minecraft.actions.PlayerClickAction;
import com.huidu.musicboxplus.module.gui.playlist.PlayListListGUI;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayHandle;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayerManager;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class TextDisplayPlayerEditGUI {
    private final String name;
    private GUI gui;
    private GUIConfigManager.TextPlayerEditConfig config;
    private MbTask watcherTask;

    public TextDisplayPlayerEditGUI(String name) {
        this.name = name;
    }

    public void open(Player player) {
        stopWatcher();
        TextDisplayHandle textPlayer = TextDisplayPlayerManager.get(this.name).orElse(null);
        if (textPlayer == null) {
            MessageUtils.send(player, Lang.TEXT_PLAYER_NOT_FOUND, "{name}", this.name);
            player.closeInventory();
            return;
        }

        this.config = GUIConfigManager.getInstance().getTextPlayerEditConfig();
        this.gui = new GUI(config.getTitle().replace("{name}", this.name),
                GUIConfigManager.getRowsForLayout(config.getLayout(), 3));
        TextDisplayPlayer.DisplayOptions options = textPlayer.getDisplayOptions();
        boolean admin = player.hasPermission(Permissions.ADMIN);

        // Shown to everyone who can open the menu (admins + public-edit guests): appearance + song.
        addConfiguredItem("info", createInfoItem(textPlayer), null);
        addConfiguredItem("toggle-name", createToggleItem("toggle-name", options.isShowName()), new ClickAction(() -> {
            options.setShowName(!options.isShowName());
            textPlayer.refreshText();
            open(player);
        }));
        addConfiguredItem("toggle-song", createToggleItem("toggle-song", options.isShowSong()), new ClickAction(() -> {
            options.setShowSong(!options.isShowSong());
            textPlayer.refreshText();
            open(player);
        }));
        addConfiguredItem("toggle-progress", createToggleItem("toggle-progress", options.isShowProgress()), new ClickAction(() -> {
            options.setShowProgress(!options.isShowProgress());
            textPlayer.refreshText();
            open(player);
        }));
        addConfiguredItem("toggle-time", createToggleItem("toggle-time", options.isShowTime()), new ClickAction(() -> {
            options.setShowTime(!options.isShowTime());
            textPlayer.refreshText();
            open(player);
        }));
        addConfiguredItem("choose-song", createConfiguredItem("choose-song"), new PlayerClickAction(p -> openSongSelector(player)));
        addConfiguredItem("choose-playlist", createConfiguredItem("choose-playlist"), new PlayerClickAction(p -> openPlaylistSelector(player)));
        addConfiguredItem("close", createConfiguredItem("close"), new PlayerClickAction(Player::closeInventory));

        // Admin-only: placement, range, billboard/orientation, public-edit toggle, delete.
        if (admin) {
            if (textPlayer.isActive()) {
                addConfiguredItem("control", createConfiguredItem("control"), new PlayerClickAction(p ->
                        TextDisplayPlayerManager.getActive(this.name).ifPresent(updated -> updated.getControl().open(player))));
            }
            addConfiguredItem("move-to-me", createConfiguredItem("move-to-me"), new PlayerClickAction(p -> {
                if (TextDisplayPlayerManager.move(this.name, p.getLocation())) {
                    MessageUtils.send(p, Lang.TEXT_PLAYER_MOVED, "{name}", this.name);
                }
                open(player);
            }));
            addConfiguredItem("adjust-x", createConfiguredItem("adjust-x",
                    "{multiplier}", String.valueOf(config.getPositionShiftMultiplier())),
                    new ClickAction(
                            event -> adjustPosition(player, -positionStep(event), 0.0),
                            event -> adjustPosition(player, positionStep(event), 0.0)));
            addConfiguredItem("adjust-z", createConfiguredItem("adjust-z",
                    "{multiplier}", String.valueOf(config.getPositionShiftMultiplier())),
                    new ClickAction(
                            event -> adjustPosition(player, 0.0, -positionStep(event)),
                            event -> adjustPosition(player, 0.0, positionStep(event))));
            addConfiguredItem("adjust-y", createConfiguredItem("adjust-y",
                    "{multiplier}", String.valueOf(config.getPositionShiftMultiplier())),
                    new ClickAction(
                            event -> adjustHeight(player, -heightStep(event)),
                            event -> adjustHeight(player, heightStep(event))));
            addConfiguredItem("range-up", createRangeItem("range-up", textPlayer.getRange()), new ClickAction(
                    () -> changeRange(player, config.getRangeStep()),
                    () -> promptRangeInput(player)));
            addConfiguredItem("range-down", createRangeItem("range-down", textPlayer.getRange()), new ClickAction(
                    () -> changeRange(player, -config.getRangeStep()),
                    () -> promptRangeInput(player)));
            addConfiguredItem("toggle-billboard", createToggleItem("toggle-billboard", options.isBillboardFixed()), new ClickAction(() -> {
                options.setBillboardFixed(!options.isBillboardFixed());
                textPlayer.applyVisualOptions();
                open(player);
            }));
            addConfiguredItem("set-facing", createConfiguredItem("set-facing"), new ClickAction(
                    () -> {
                        options.setBillboardFixed(true);
                        options.setFixedYaw(player.getLocation().getYaw() + 180.0f);
                        textPlayer.applyVisualOptions();
                        open(player);
                    },
                    () -> {
                        options.setFixedYaw(0.0f);
                        textPlayer.applyVisualOptions();
                        open(player);
                    }));
            addConfiguredItem("toggle-double-sided", createToggleItem("toggle-double-sided", options.isDoubleSided()), new ClickAction(() -> {
                options.setDoubleSided(!options.isDoubleSided());
                textPlayer.applyVisualOptions();
                open(player);
            }));
            addConfiguredItem("toggle-public-edit", createToggleItem("toggle-public-edit", options.isAllowPublicEdit()), new ClickAction(() -> {
                options.setAllowPublicEdit(!options.isAllowPublicEdit());
                open(player);
            }));
            addConfiguredItem("delete", createConfiguredItem("delete"), new PlayerClickAction(p -> {
                if (TextDisplayPlayerManager.delete(this.name)) {
                    MessageUtils.send(player, Lang.TEXT_PLAYER_DELETED, "{name}", this.name);
                }
                player.closeInventory();
            }));
        }
        this.gui.open(player);
        startWatcher(player);
    }

    private void startWatcher(Player player) {
        this.watcherTask = Scheduler.entityTimer(player, () -> {
            if (!player.isOnline() || this.gui == null
                    || player.getOpenInventory().getTopInventory() != this.gui.getInventory()) {
                stopWatcher();
                return;
            }
            if (TextDisplayPlayerManager.get(this.name).isEmpty()) {
                stopWatcher();
                player.closeInventory();
            }
        }, 10L, 10L);
    }

    private void stopWatcher() {
        if (this.watcherTask != null) {
            this.watcherTask.cancel();
            this.watcherTask = null;
        }
    }

    private ItemStack createInfoItem(TextDisplayHandle textPlayer) {
        MusicBoxSong song = textPlayer.getDisplaySong();
        String songName = song != null ? song.getName() : Lang.NO_MUSIC_PLAYING.toString();
        return createConfiguredItem(
            "info",
            "{name}", this.name,
            "{song}", MiniMessageUtils.toPlainText(songName),
            "{range}", String.valueOf(textPlayer.getRange())
        );
    }

    private ItemStack createToggleItem(String key, boolean enabled) {
        GUIConfigManager.ToggleStatusConfig toggle = GUIConfigManager.getInstance().getToggleStatusConfig();
        String status = enabled ? toggle.getEnabled() : toggle.getDisabled();
        return createConfiguredItem(key, "{status}", status);
    }

    private ItemStack createRangeItem(String key, int range) {
        return createConfiguredItem(key, "{range}", String.valueOf(range));
    }

    private void changeRange(Player player, int delta) {
        int current = TextDisplayPlayerManager.get(this.name).map(TextDisplayHandle::getRange).orElse(16);
        TextDisplayPlayerManager.setRange(this.name, current + delta);
        open(player);
    }

    private void adjustPosition(Player player, double deltaX, double deltaZ) {
        TextDisplayPlayerManager.get(this.name).ifPresent(tp -> tp.adjustPosition(deltaX, deltaZ));
        open(player);
    }

    private void adjustHeight(Player player, double delta) {
        TextDisplayPlayerManager.get(this.name).ifPresent(tp -> tp.adjustHeight(delta));
        open(player);
    }

    private double heightStep(InventoryClickEvent event) {
        double step = this.config.getHeightStep();
        return event.getClick().isShiftClick() ? step * this.config.getPositionShiftMultiplier() : step;
    }

    private double positionStep(InventoryClickEvent event) {
        double step = this.config.getPositionStep();
        return event.getClick().isShiftClick() ? step * this.config.getPositionShiftMultiplier() : step;
    }

    private void promptRangeInput(Player player) {
        player.closeInventory();
        GUIInputManager.getInstance().requestInput(
            player,
            GUIInputManager.InputType.SEARCH_QUERY,
            Lang.TEXT_PLAYER_RANGE_INPUT.toComponent(
                "{min}", String.valueOf(TextDisplayPlayerManager.MIN_RANGE),
                "{max}", String.valueOf(TextDisplayPlayerManager.MAX_RANGE)
            ),
            new GUIInputManager.InputCallback() {
                @Override
                public void onInputReceived(Player p, String input) {
                    try {
                        int value = Integer.parseInt(input.trim());
                        if (TextDisplayPlayerManager.setRange(name, value)) {
                            int applied = TextDisplayPlayerManager.get(name).map(TextDisplayHandle::getRange).orElse(value);
                            MessageUtils.send(p, Lang.TEXT_PLAYER_RANGE_SET, "{name}", name, "{range}", String.valueOf(applied));
                        }
                    } catch (NumberFormatException e) {
                        MessageUtils.send(p, Lang.TEXT_PLAYER_INVALID_NUMBER, "{input}", input);
                    }
                    Scheduler.entity(p, () -> new TextDisplayPlayerEditGUI(name).open(p));
                }

                @Override
                public void onInputCancelled(Player p) {
                    Scheduler.entity(p, () -> new TextDisplayPlayerEditGUI(name).open(p));
                }
            });
    }

    private void addConfiguredItem(String key, ItemStack item, com.huidu.musicboxplus.module.gui.minecraft.InventoryAction action) {
        int slot = config.getSlotForButton(key);
        if (slot >= 0 && item != null) {
            this.gui.addItem(slot, item, action);
        }
    }

    private ItemStack createConfiguredItem(String key, String... replacements) {
        GUIConfigManager.HotbarButtonConfig buttonConfig = config.getButton(key);
        if (buttonConfig == null) {
            return null;
        }
        String name = applyPlaceholders(buttonConfig.getName(), replacements);
        List<String> lore = buttonConfig.getLore().stream()
            .map(line -> applyPlaceholders(line, replacements))
            .toList();
        return ItemUtils.createStack(buttonConfig.getMaterial(), name, lore, buttonConfig.getCustomModelData(),
                buttonConfig.getItemModel(), buttonConfig.getCraftEngineItem());
    }

    private String applyPlaceholders(String input, String... replacements) {
        String output = input == null ? "" : input;
        if (replacements == null) {
            return output;
        }
        for (int i = 0; i < replacements.length - 1; i += 2) {
            output = output.replace(replacements[i], replacements[i + 1]);
        }
        return output;
    }

    private void openSongSelector(Player player) {
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        SongContainerGUI.SongGUIParams params = SongContainerGUI.SongGUIParams.builder()
            .onSongLeftClick((w, data) -> {
                MusicBoxSong song = data.getData();
                if (song == null) {
                    return;
                }
                TextDisplayPlayerManager.setSong(this.name, song);
                MessageUtils.send(player, Lang.TEXT_PLAYER_SONG_SET, "{name}", this.name, "{song}", song.getName());
                new TextDisplayPlayerEditGUI(this.name).open(player);
            })
            .onContainerRightClick((w, data) -> {
                if (data == null || data.getData() == null) {
                    return;
                }
                List<MusicBoxSong> songs = data.getData().getAllSongs();
                if (songs == null || songs.isEmpty()) {
                    return;
                }
                applyPlaylist(player, songs);
            })
            .extraContainerLore(data -> Lang.TEXT_PLAYER_FOLDER_LORE.toList())
            .build();
        gui.openPage(0, params, "textplayer-songs", () -> new TextDisplayPlayerEditGUI(this.name).open(player));
    }

    private void openPlaylistSelector(Player player) {
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        PlayListListGUI.openAsync(
            wrapper,
            model -> new ClickAction(() -> {
                List<MusicBoxSong> songs = model.getAllSongs();
                if (songs == null || songs.isEmpty()) {
                    MessageUtils.send(player, Lang.TEXT_PLAYER_PLAYLIST_EMPTY);
                    return;
                }
                applyPlaylist(player, songs);
            }),
            null,
            () -> new TextDisplayPlayerEditGUI(this.name).open(player)
        );
    }

    private void applyPlaylist(Player player, List<MusicBoxSong> songs) {
        TextDisplayPlayerManager.setPlaylist(this.name, new ListPlaylist(songs, true));
        MessageUtils.send(player, Lang.TEXT_PLAYER_PLAYLIST_SET,
            "{name}", this.name, "{count}", String.valueOf(songs.size()));
        new TextDisplayPlayerEditGUI(this.name).open(player);
    }
}
