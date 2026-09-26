package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public abstract class AbstractSelect implements SubCommand {
    private final String permission;
    private static final String ADMIN_PERMISSION = Permissions.ADMIN;

    public AbstractSelect(String permission) {
        this.permission = permission;
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(this.permission);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            handleNoArgs(sender);
            return;
        }
        
        String firstArg = args[0];
        
        if (firstArg.startsWith("-")) {
            handleFlagArg(sender, args);
            return;
        }
        
        if (args.length == 1) {
            handleSingleArg(sender, firstArg);
            return;
        }
        
        handleSongArg(sender, args);
    }
    
    private void handleNoArgs(CommandSender sender) {
        if (sender instanceof Player) {
            this.noArgs(sender, (Player) sender);
        } else {
            MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
        }
    }
    
    private void handleFlagArg(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
            return;
        }
        
        String flag = args[0];
        
        if (args.length == 1) {
            this.noArgsWithFlag(sender, (Player) sender, flag);
            return;
        }
        
        if (checkAdminPermission(sender)) {
            return;
        }
        
        Player target = getOnlinePlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        
        this.noArgsWithFlag(sender, target, flag);
    }
    
    private void handleSingleArg(CommandSender sender, String playerName) {
        if (checkAdminPermission(sender)) {
            return;
        }
        
        Player target = getOnlinePlayer(sender, playerName);
        if (target == null) {
            return;
        }
        
        this.noArgs(sender, target);
    }
    
    private void handleSongArg(CommandSender sender, String[] args) {
        if (checkAdminPermission(sender)) {
            return;
        }
        
        String songName = args[1].replace('_', ' ');
        MusicBoxSong song = MusicBoxSongManager.findByName(songName).orElse(null);
        
        if (song == null) {
            MessageUtils.send(sender, Lang.SONG_NOT_FOUND);
            return;
        }
        
        Player target = getOnlinePlayer(sender, args[0]);
        if (target == null) {
            return;
        }
        
        this.processSong(sender, target, song, args);
    }
    
    private boolean checkAdminPermission(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            MessageUtils.send(sender, Lang.NO_PERMISSIONS);
            return true;
        }
        return false;
    }
    
    private Player getOnlinePlayer(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            MessageUtils.send(sender, Lang.PLAYER_OFFLINE, "{player}", playerName);
        }
        return target;
    }

    protected abstract void noArgs(CommandSender sender, Player player);

    protected void noArgsWithFlag(CommandSender sender, Player player, String flag) {
        this.noArgs(sender, player);
    }

    protected abstract void processSong(CommandSender sender, Player target, MusicBoxSong song, String[] args);

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return getBasicTabComplete(sender, args);
        }
        
        return getAdminTabComplete(sender, args);
    }
    
    private List<String> getBasicTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player) {
            Stream<String> flags = Stream.of("-s", "--single");
            return StringUtils.tabCompletePrepare(args, flags);
        }
        return Collections.emptyList();
    }
    
    private List<String> getAdminTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            Stream<String> players = Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName);
            Stream<String> flags = Stream.of("-s", "--single");
            return StringUtils.tabCompletePrepare(args, Stream.concat(flags, players));
        }
        
        if (args.length == 2) {
            Stream<String> songs = MusicBoxSongManager.getRootContainer()
                    .getAllSongs().stream()
                    .map(MusicBoxSong::getName)
                    .map(s -> s.replace(' ', '_'));
            return StringUtils.tabCompletePrepare(args, 2, songs);
        }
        
        return Collections.emptyList();
    }
}
