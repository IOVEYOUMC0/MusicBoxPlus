package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Paths;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ReloadExecutor
implements SubCommand {
    private static final List<String> RELOAD_TYPES = Arrays.asList("all", "config", Paths.LANG_DIR, Paths.SONGS_DIR, "gui", "database", "aliases");

    @Override
    public void execute(CommandSender sender, String[] args) {
        String type = args.length > 0 ? args[0].toLowerCase() : "all";
        if (!RELOAD_TYPES.contains(type)) {
            MessageUtils.send(sender, Lang.RELOAD_UNKNOWN_TYPE, "{type}", type);
            this.sendUsage(sender);
            return;
        }
        Scheduler.global(() -> {
            long startTime = System.currentTimeMillis();
            MusicBox.getInstance().reloadPartialAsync(type).whenComplete((unused, throwable) -> {
                Scheduler.global(() -> {
                    if (throwable != null) {
                        MusicBox.getInstance().getLogger().log(Level.SEVERE, "Plugin reload failed", throwable);
                        MessageUtils.send(sender, Lang.ERROR_OCCURRED);
                        return;
                    }

                    long endTime = System.currentTimeMillis();
                    MessageUtils.send(sender, Lang.RELOAD_SUCCESS_WITH_TIME, "{time}", String.valueOf(endTime - startTime));
                });
            });
        });
    }

    private void sendUsage(CommandSender sender) {
        MessageUtils.send(sender, Lang.RELOAD_USAGE_TITLE);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_ALL);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_CONFIG);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_LANG);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_SONGS);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_GUI);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_DATABASE);
        MessageUtils.send(sender, Lang.RELOAD_USAGE_ALIASES);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.RELOAD);
    }

    @Override
    public List<String> tabComplete(CommandSender player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return RELOAD_TYPES.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
