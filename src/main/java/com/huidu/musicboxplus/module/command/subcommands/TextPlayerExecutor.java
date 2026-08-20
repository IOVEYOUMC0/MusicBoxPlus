package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.gui.song.SongContainerGUI;
import com.huidu.musicboxplus.module.gui.textplayer.TextDisplayPlayerEditGUI;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayPlayerManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Stream;

public class TextPlayerExecutor implements SubCommand {
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }

        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        String action = args[0].toLowerCase();
        String name = args[1];
        switch (action) {
            case "create":
                handleCreate(player, name, args);
                break;
            case "delete":
                handleDelete(sender, name);
                break;
            case "songs":
                handleSongs(player, name);
                break;
            case "edit":
                handleEdit(player, name);
                break;
            default:
                sendUsage(sender);
                break;
        }
    }

    private void handleCreate(Player player, String name, String[] args) {
        if (args.length < 3) {
            // No song specified: create a song-less placeholder display. Assign a song later
            // via /musicboxplus textplayer songs <name> or the edit menu.
            if (TextDisplayPlayerManager.createIdle(name, player.getLocation(), 16) == null) {
                MessageUtils.send(player, "&cText player module is disabled");
                return;
            }
            new TextDisplayPlayerEditGUI(name).open(player);
            MessageUtils.send(player, "&aCreated empty text player &f" + name + "&a — pick a song from the menu");
            return;
        }
        String songName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).replace('_', ' ');
        MusicBoxSong song = MusicBoxSongManager.findByName(songName).orElse(null);
        if (song == null) {
            MessageUtils.send(player, Lang.SONG_NOT_FOUND);
            return;
        }

        TextDisplayPlayer textPlayer = TextDisplayPlayerManager.create(name, song, player.getLocation(), 16);
        if (textPlayer == null) {
            MessageUtils.send(player, "&cText player module is disabled");
            return;
        }
        textPlayer.getControl().open(player);
        MessageUtils.send(player, "&aCreated text player &f" + name + "&a for &f" + song.getName());
    }

    private void handleDelete(CommandSender sender, String name) {
        if (TextDisplayPlayerManager.delete(name)) {
            MessageUtils.send(sender, "&aDeleted text player &f" + name);
        } else {
            MessageUtils.send(sender, "&cText player not found: &f" + name);
        }
    }

    private void handleSongs(Player player, String name) {
        if (TextDisplayPlayerManager.get(name).isEmpty()) {
            MessageUtils.send(player, "&cText player not found: &f" + name);
            return;
        }

        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        SongContainerGUI gui = new SongContainerGUI(MusicBoxSongManager.getRootContainer(), wrapper);
        SongContainerGUI.SongGUIParams params = SongContainerGUI.SongGUIParams.builder()
            .onSongLeftClick((w, data) -> {
                MusicBoxSong song = data.getData();
                if (song == null) {
                    return;
                }
                TextDisplayPlayerManager.setSong(name, song);
                TextDisplayPlayerManager.getActive(name).ifPresent(updated -> updated.getControl().open(player));
                player.sendMessage(com.huidu.musicboxplus.common.utils.MiniMessageUtils.processComponent("&aSet text player &f" + name + "&a song to &f" + song.getName()));
            })
            .build();
        gui.openPage(0, params, "textplayer-songs");
    }

    private void handleEdit(Player player, String name) {
        if (TextDisplayPlayerManager.get(name).isEmpty()) {
            MessageUtils.send(player, "&cText player not found: &f" + name);
            return;
        }
        new TextDisplayPlayerEditGUI(name).open(player);
    }

    private void sendUsage(CommandSender sender) {
        MessageUtils.send(sender, "&cUsage:");
        MessageUtils.send(sender, "&7/musicboxplus textplayer create <name> [song]");
        MessageUtils.send(sender, "&7/musicboxplus textplayer delete <name>");
        MessageUtils.send(sender, "&7/musicboxplus textplayer songs <name>");
        MessageUtils.send(sender, "&7/musicboxplus textplayer edit <name>");
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return StringUtils.tabCompletePrepare(args, Stream.of("create", "delete", "songs", "edit"));
        }
        if (args.length == 2) {
            if ("delete".equalsIgnoreCase(args[0]) || "songs".equalsIgnoreCase(args[0]) || "edit".equalsIgnoreCase(args[0])) {
                return StringUtils.tabCompletePrepare(args, 2, TextDisplayPlayerManager.getNames().stream());
            }
            return List.of();
        }
        if (args.length >= 3 && "create".equalsIgnoreCase(args[0])) {
            Stream<String> songs = MusicBoxSongManager.getRootContainer().getAllSongs().stream()
                .map(MusicBoxSong::getName)
                .map(songName -> songName.replace(' ', '_'));
            return StringUtils.tabCompletePrepare(args, args.length, songs);
        }
        return List.of();
    }
}
