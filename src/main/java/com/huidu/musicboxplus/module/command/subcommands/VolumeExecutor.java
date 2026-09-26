package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Usage: /musicboxplus volume [amount|up|down|reset]
public class VolumeExecutor implements SubCommand {

    private static final List<String> ACTIONS = Arrays.asList("up", "+", "increase", "down", "-", "decrease", "reset", "default");

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }
        
        Player player = (Player) sender;
        VolumeManager vm = VolumeManager.getInstance();
        
        if (args.length == 0) {
            vm.sendVolumeMessage(player);
            return;
        }
        
        String action = args[0].toLowerCase();
        
        switch (action) {
            case "up":
            case "+":
            case "increase":
                int newVolUp = vm.increaseVolume(player);
                MessageUtils.send(player, Lang.VOLUME_INCREASED.toString().replace("{volume}", String.valueOf(newVolUp)));
                vm.sendVolumeMessage(player);
                break;
                
            case "down":
            case "-":
            case "decrease":
                int newVolDown = vm.decreaseVolume(player);
                MessageUtils.send(player, Lang.VOLUME_DECREASED.toString().replace("{volume}", String.valueOf(newVolDown)));
                vm.sendVolumeMessage(player);
                break;
                
            case "reset":
            case "default":
                vm.resetVolume(player);
                MessageUtils.send(player, Lang.VOLUME_RESET);
                vm.sendVolumeMessage(player);
                break;
                
            default:
                try {
                    int amount = Integer.parseInt(action);
                    if (amount < 0 || amount > 100) {
                        MessageUtils.send(player, Lang.VOLUME_INVALID);
                        return;
                    }
                    int newVolume = vm.setVolumeByAmount(player, amount);
                    MessageUtils.send(player, Lang.VOLUME_SET.toString().replace("{volume}", String.valueOf(newVolume)));
                    vm.sendVolumeMessage(player);
                } catch (NumberFormatException e) {
                    MessageUtils.send(player, Lang.VOLUME_INVALID);
                }
                break;
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return ACTIONS.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.VOLUME);
    }
}
