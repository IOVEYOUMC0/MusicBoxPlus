package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.gui.GUIActions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class PlayExecutor implements SubCommand {
    private static final String ADMIN_PERMISSION = Permissions.ADMIN;
    private static final String PLAY_OTHER_PERMISSION = Permissions.PLAY_OTHER;
    private static final List<String> MODE_OPTIONS = List.of(
        "speaker", "--speaker", "-speaker", "-sp",
        "radio", "--radio", "-radio", "-r",
        "normal", "--normal", "-normal", "-n",
        "silent", "--silent", "-silent", "-si",
        "unsilent", "--unsilent", "-unsilent", "-u",
        "audible", "--audible"
    );

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.USE);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                GUIActions.openDefaultInventory(PlayerWrapper.getInstance(player));
            } else {
                MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
            }
            return;
        }

        ParsedPlayTarget parsed = resolveTarget(sender, args);
        if (parsed == null) {
            return;
        }

        MusicBoxSong song = MusicBoxSongManager.findByName(args[parsed.songIndex()].replace('_', ' ')).orElse(null);
        if (song == null) {
            MessageUtils.send(sender, Lang.SONG_NOT_FOUND);
            return;
        }

        PlayerWrapper wrapper = PlayerWrapper.getInstance(parsed.target());
        boolean originalSpeaker = wrapper.isSpeaker();
        boolean originalSilent = wrapper.isSilent();

        // Parse the whole flag list before touching the wrapper, so a typo late in the line does
        // not leave the earlier flags applied.
        Boolean speakerMode = null;
        Boolean silentMode = null;
        for (int i = parsed.songIndex() + 1; i < args.length; i++) {
            switch (normalizeModeOption(args[i])) {
                case "speaker" -> speakerMode = true;
                case "radio", "normal" -> speakerMode = false;
                case "silent" -> silentMode = true;
                case "unsilent" -> silentMode = false;
                default -> {
                    MessageUtils.send(sender, Lang.UNKNOWN_TYPE);
                    return;
                }
            }
        }
        if (Boolean.TRUE.equals(speakerMode) && !canControlOtherPlayers(sender) && !wrapper.canSwitch()) {
            MessageUtils.send(sender, Lang.CANT_SWITCH);
            return;
        }
        if (speakerMode != null) {
            wrapper.setSpeaker(speakerMode);
        }
        if (silentMode != null) {
            wrapper.setSilent(silentMode);
        }

        sendModeConflictFeedback(sender, originalSpeaker, originalSilent, speakerMode != null, silentMode != null, wrapper);
        wrapper.play(song);
        sendPlayFeedback(sender, parsed.target(), song, wrapper);
    }

    private ParsedPlayTarget resolveTarget(CommandSender sender, String[] args) {
        if (canControlOtherPlayers(sender) && args.length >= 2) {
            if (!isModeOption(args[0]) && !isModeOption(args[1])) {
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    MessageUtils.send(sender, Lang.PLAYER_OFFLINE, "{player}", args[0]);
                    return null;
                }
                int songIndex = findSongIndex(args, 1);
                if (songIndex != -1) {
                    return new ParsedPlayTarget(target, songIndex);
                }
            }
        }

        if (sender instanceof Player player) {
            int songIndex = findSongIndex(args, 0);
            if (songIndex == -1) {
                MessageUtils.send(sender, Lang.SONG_NOT_FOUND);
                return null;
            }
            return new ParsedPlayTarget(player, songIndex);
        }

        MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
        return null;
    }

    private boolean canControlOtherPlayers(CommandSender sender) {
        return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(PLAY_OTHER_PERMISSION);
    }

    // The song is the first argument that is not a mode flag, or one that names a real song --
    // the bare flag forms are also legal song names.
    private int findSongIndex(String[] args, int startIndex) {
        for (int i = startIndex; i < args.length; i++) {
            if (!isModeOption(args[i]) || MusicBoxSongManager.findByName(args[i].replace('_', ' ')).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private boolean isModeOption(String rawOption) {
        return MODE_OPTIONS.contains(rawOption.toLowerCase(Locale.ROOT));
    }

    private String normalizeModeOption(String rawOption) {
        String option = rawOption.toLowerCase(Locale.ROOT);
        switch (option) {
            case "--speaker":
            case "-speaker":
            case "-sp":
                return "speaker";
            case "--radio":
            case "-radio":
            case "-r":
                return "radio";
            case "--normal":
            case "-normal":
            case "-n":
                return "normal";
            case "--silent":
            case "-silent":
            case "-si":
                return "silent";
            case "--unsilent":
            case "-unsilent":
            case "-u":
            case "audible":
            case "--audible":
                return "unsilent";
            default:
                return option;
        }
    }

    private void sendPlayFeedback(CommandSender sender, Player target, MusicBoxSong song, PlayerWrapper wrapper) {
        String mode = wrapper.getLocalizedPlaybackMode();
        String silentState = wrapper.getLocalizedSilentState();
        if (sender == target) {
            MessageUtils.send(sender, Lang.PLAY_FEEDBACK_SELF, "{song}", song.getName(), "{mode}", mode, "{silent}", silentState);
            return;
        }

        MessageUtils.send(sender, Lang.PLAY_FEEDBACK_OTHER, "{song}", song.getName(), "{player}", target.getName(), "{mode}", mode, "{silent}", silentState);
        MessageUtils.send(target, Lang.PLAY_FEEDBACK_TARGET, "{song}", song.getName(), "{mode}", mode, "{silent}", silentState);
    }

    private void sendModeConflictFeedback(CommandSender sender, boolean originalSpeaker, boolean originalSilent, boolean speakerRequested, boolean silentRequested, PlayerWrapper wrapper) {
        if (speakerRequested && originalSpeaker != wrapper.isSpeaker()) {
            MessageUtils.send(sender, Lang.PLAY_MODE_FEEDBACK, "{mode}", wrapper.getLocalizedPlaybackMode());
        }
        if (silentRequested && originalSilent != wrapper.isSilent()) {
            MessageUtils.send(sender, Lang.PLAY_SILENT_FEEDBACK, "{silent}", wrapper.getLocalizedSilentState());
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (canControlOtherPlayers(sender)) {
            if (args.length == 1) {
                Stream<String> players = Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName);
                Stream<String> songs = MusicBoxSongManager.getRootContainer().getAllSongs().stream()
                    .map(MusicBoxSong::getName)
                    .map(name -> name.replace(' ', '_'));
                return StringUtils.tabCompletePrepare(args, Stream.concat(players, Stream.concat(songs, MODE_OPTIONS.stream())));
            }
            if (args.length == 2) {
                if (Bukkit.getPlayer(args[0]) != null && !isModeOption(args[1])) {
                    Stream<String> songs = MusicBoxSongManager.getRootContainer().getAllSongs().stream()
                        .map(MusicBoxSong::getName)
                        .map(name -> name.replace(' ', '_'));
                    return StringUtils.tabCompletePrepare(args, 2, Stream.concat(songs, MODE_OPTIONS.stream()));
                }
                return StringUtils.tabCompletePrepare(args, 2, MODE_OPTIONS.stream());
            }
            return StringUtils.tabCompletePrepare(args, args.length, MODE_OPTIONS.stream());
        }

        if (args.length == 1) {
            Stream<String> songs = MusicBoxSongManager.getRootContainer().getAllSongs().stream()
                .map(MusicBoxSong::getName)
                .map(name -> name.replace(' ', '_'));
            return StringUtils.tabCompletePrepare(args, songs);
        }
        return StringUtils.tabCompletePrepare(args, args.length, MODE_OPTIONS.stream());
    }

    private record ParsedPlayTarget(Player target, int songIndex) {
    }
}
