package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

// Usage: /musicboxplus stop [player]
public class StopExecutor implements SubCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player target;
        
        if (args.length > 0) {
            if (!sender.hasPermission(Permissions.STOP_OTHERS)) {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
                return;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                MessageUtils.send(sender, Lang.PLAYER_NOT_FOUND.toString().replace("{player}", args[0]));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }
        
        stopPlayer(target, sender);
    }

    private void stopPlayer(Player target, CommandSender sender) {
        PlayerWrapper wrapper = PlayerWrapper.getInstance(target);
        if (wrapper == null) {
            MessageUtils.send(sender, Lang.ERROR_OCCURRED);
            return;
        }
        
        MusicBoxSongPlayer player = wrapper.getActivePlayer();
        if (player != null && !player.isDestroyed()) {
            // Fires MusicBoxStopEvent first; a listener may veto the stop.
            if (player.stop()) {
                return;
            }
            player.destroy(DestroyReason.MANUAL_STOP);
            if (sender.equals(target)) {
                MessageUtils.send(sender, Lang.MUSIC_STOPPED);
            } else {
                MessageUtils.send(sender, Lang.MUSIC_STOPPED_OTHER.toString().replace("{player}", target.getName()));
                MessageUtils.send(target, Lang.MUSIC_STOPPED_BY_OTHER);
            }
            return;
        }
        
        boolean found = false;
        for (AbstractBlockPlayer blockPlayer : AbstractBlockPlayer.getAll()) {
            if (blockPlayer.isDestroyed()) continue;
            if (blockPlayer.getPlayers().contains(target.getUniqueId())) {
                // Unsubscribe this listener only: getPlayers() is the in-range set, not an
                // ownership list, so destroying would silence a shared block player for everyone.
                blockPlayer.removePlayer(target);
                found = true;
                break;
            }
        }
        
        if (found) {
            if (sender.equals(target)) {
                MessageUtils.send(sender, Lang.MUSIC_STOPPED);
            } else {
                MessageUtils.send(sender, Lang.MUSIC_STOPPED_OTHER.toString().replace("{player}", target.getName()));
                MessageUtils.send(target, Lang.MUSIC_STOPPED_BY_OTHER);
            }
        } else {
            if (sender.equals(target)) {
                MessageUtils.send(sender, Lang.NO_MUSIC_PLAYING);
            } else {
                MessageUtils.send(sender, Lang.NO_MUSIC_PLAYING_OTHER.toString().replace("{player}", target.getName()));
            }
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission(Permissions.STOP_OTHERS)) {
            return null;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.STOP) || sender.hasPermission(Permissions.STOP_OTHERS);
    }
}
