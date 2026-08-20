package com.huidu.musicboxplus.module.command;

import com.huidu.musicboxplus.common.Permissions;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

// Contract for one /musicboxplus sub-command. A sub-command is only responsible for its
// own arguments; routing and permission gating live in the dispatcher.
public interface SubCommand {

    void execute(CommandSender sender, String[] args);

    default boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.USE);
    }

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}