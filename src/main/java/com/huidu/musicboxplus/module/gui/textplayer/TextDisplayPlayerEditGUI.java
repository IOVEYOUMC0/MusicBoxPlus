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
            MessageUtils.send(player, "&cText player not found: &f" + this.name);
            player.closeInventory();
            return;
        }

        this.config = GUIConfigManager.getInstance().getTextPlayerEditConfig();
        this.gui = new GUI(config.getTitle().replace("{name}", this.name), 3);
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
                    MessageUtils.send(p, "&aMoved text player &f" + this.name + "&a to your position");
                }
                open(player);
            }));
            addConfiguredItem("raise", createConfiguredItem("raise"), new ClickAction(() -> {
                TextDisplayPlayerManager.get(this.name).ifPresent(tp -> tp.adjustHeight(0.25));
                open(player);
            }));
            addConfiguredItem("lower", createConfiguredItem("lower"), new ClickAction(() -> {
                TextDisplayPlayerManager.get(this.name).ifPresent(tp -> tp.adjustHeight(-0.25));
                open(player);
            }));
            addConfiguredItem("range-up", createRangeItem("range-up", textPlayer.getRange()), new ClickAction(
                    () -> changeRange(player, TextDisplayPlayerManager.RANGE_STEP),
                    () -> promptRangeInput(player)));
            addConfiguredItem("range-down", createRangeItem("range-down", textPlayer.getRange()), new ClickAction(
                    () -> changeRange(player, -TextDisplayPlayerManager.RANGE_STEP),
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
            addConfiguredItem("toggle-public-edit", createToggleItem("toggle-public-edit", options.isAllowPublicEdit()), new ClickAction(() -> {
                options.setAllowPublicEdit(!options.isAllowPublicEdit());
                open(player);
            }));
            addConfiguredItem("delete", createConfiguredItem("delete"), new PlayerClickAction(p -> {
                if (TextDisplayPlayerManager.delete(this.name)) {
                    MessageUtils.send(player, "&aDeleted text player &f" + this.name);
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

    private void promptRangeInput(Player player) {
        player.closeInventory();
        GUIInputManager.getInstance().requestInput(
            player,
            GUIInputManager.InputType.SEARCH_QUERY,
            MiniMessageUtils.processComponent("<yellow>输入播放范围 (" + TextDisplayPlayerManager.MIN_RANGE + "-" + TextDisplayPlayerManager.MAX_RANGE + "):</yellow>"),
            new GUIInputManager.InputCallback() {
                @Override
                public void onInputReceived(Player p, String input) {
                    try {
                        int value = Integer.parseInt(input.trim());
                        if (TextDisplayPlayerManager.setRange(name, value)) {
                            int applied = TextDisplayPlayerManager.get(name).map(TextDisplayHandle::getRange).orElse(value);
                            MessageUtils.send(p, "&aSet text player &f" + name + "&a range to &f" + applied);
                        }
                    } catch (NumberFormatException e) {
                        MessageUtils.send(p, "&cInvalid number: &f" + input);
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
        return ItemUtils.createStack(buttonConfig.getMaterial(), name, lore, buttonConfig.getCustomModelData());
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
                player.sendMessage(MiniMessageUtils.processComponent("&aSet text player &f" + this.name + "&a song to &f" + song.getName()));
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
                TextDisplayPlayerManager.setPlaylist(this.name, new ListPlaylist(songs, true));
                player.sendMessage(MiniMessageUtils.processComponent("&aSet text player &f" + this.name + "&a playlist to &f" + songs.size() + " &asongs"));
                new TextDisplayPlayerEditGUI(this.name).open(player);
            })
            .extraContainerLore(data -> List.of("<gray>右键将整个文件夹设为播放列表</gray>"))
            .build();
        gui.openPage(0, params, "textplayer-songs");
    }

    private void openPlaylistSelector(Player player) {
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        PlayListListGUI.openAsync(
            wrapper,
            model -> new ClickAction(() -> {
                List<MusicBoxSong> songs = model.getAllSongs();
                if (songs == null || songs.isEmpty()) {
                    player.sendMessage(MiniMessageUtils.processComponent("&cThat playlist is empty"));
                    return;
                }
                TextDisplayPlayerManager.setPlaylist(this.name, new ListPlaylist(songs, true));
                player.sendMessage(MiniMessageUtils.processComponent("&aSet text player &f" + this.name + "&a playlist to &f" + songs.size() + " &asongs"));
                new TextDisplayPlayerEditGUI(this.name).open(player);
            }),
            null,
            () -> new TextDisplayPlayerEditGUI(this.name).open(player)
        );
    }
}
